# Guidance to agentic coding tools when working with code in this repository

## Role of Coding Agents

- **Do not change files in the main working tree unless explicitly asked.** For the time
  being, your role is primarily that of a reviewer: report findings and wait for an explicit
  request before editing.
- To experiment or try out changes, create a git worktree and work there freely.
- Builds can modify tracked sources: the license plugin rewrites file headers during
  `process-sources` (see below). Another reason to build in a worktree when in doubt.

## Project Overview

A collection of advanced plugins for Apache Log4j2: filters, appenders, and testing utilities.
Published to Maven Central as `com.github.mlangc:more-log4j2`. Requires Java 17+.

[README.MD](README.MD) is the user-facing reference for every plugin (configuration tables,
usage examples, architecture diagram). Read the relevant section before changing a plugin, and
keep it in sync when behaviour or configuration changes. This file only records what the
README and the code don't make obvious.

## Build Commands

Use the Maven Wrapper (`./mvnw`), not `mvn`:

```bash
# Build and test all modules (what CI runs)
./mvnw -B package

# Run all tests (JaCoCo coverage runs automatically)
./mvnw test

# Run a single test class or method in core; -pl keeps the -Dtest filter away from
# modules that don't have the test
./mvnw -Dtest=ClassName test -pl core -am
./mvnw -Dtest='ClassName#methodName' test -pl core -am

# Same for a test in junit/: -am pulls in core, where the filter matches nothing,
# so the extra flag is required
./mvnw -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false test -pl junit -am

# Mutation testing (PIT) — always scope with -DtargetClasses (class or package glob);
# the full suite can take 30+ minutes. Requires compiled classes first:
./mvnw test-compile -pl core -am -q && ./mvnw org.pitest:pitest-maven:mutationCoverage -pl core -am -DtargetClasses='com.github.mlangc.more.log4j2.filters.ThrottlingFilter'
```

License headers are added and updated automatically by every build (`update-file-header` is
bound to `process-sources`), so don't write them by hand. To update them without a full
build: `./mvnw org.codehaus.mojo:license-maven-plugin:update-file-header`.

### Dependency updates

All dependency and plugin versions live in `parent/pom.xml` `<properties>`.

```bash
# Check for updates
./mvnw -pl parent versions:display-property-updates      # console summary
./mvnw -pl parent versions:property-updates-report       # HTML in parent/target/site/

# Apply all available updates, then review with git and run ./mvnw -B package
./mvnw -pl parent versions:update-properties -DgenerateBackupPoms=false
```

The `versions-maven-plugin` config in `parent/pom.xml` deliberately constrains updates:

- `<allowMajorUpdates>false</allowMajorUpdates>` keeps `display-*` / `update-*` off major
  bumps. A CLI `-DallowMajorUpdates=true` does *not* override the POM config.
- An `<ignoredVersions>` regex filters pre-releases (`alpha`/`beta`/`rc`/`Mn`) everywhere,
  including the `-report` goals.
- The `-report` goals still *list* major versions in their own column (they ignore
  `allowMajorUpdates`).
- Always pass `-DgenerateBackupPoms=false` and rely on git — a stale
  `pom.xml.versionsBackup` left in the tree makes `versions:revert` restore the wrong state,
  and `*.versionsBackup` is gitignored, so `git status` won't show it.

## Module Structure

- **`core/`** — All filters, appenders, and the LogCaptor API
- **`junit/`** — `AsyncHttpAppenderFlushingTestExecutionListener` for test integration
- **`parent/`** — Shared POM configuration and dependency versions
- **root `pom.xml`** (`more-log4j2-bom`) — Bill of Materials for consumers using multiple
  modules; also the reactor/aggregator POM
- **`scripts/`** — Bash development tooling (flaky-test finder, Surefire report extraction);
  see [scripts/CLAUDE.md](scripts/CLAUDE.md)

## Architecture

### Plugin System

All components use Log4j2's `@Plugin` annotation and are auto-discovered via the Log4j2
plugin annotation processor. The XML element name comes from `@Plugin(name=...)` and can
differ from the class name (`AsyncHttpAppender` → `<AsyncHttp>`, `CapturingAppender` →
`<Captor>`, `NullAppender` → `<Null>`).

Appenders extend `AbstractAppender`. Filters do *not* uniformly extend `AbstractFilter`:
`ThrottlingFilter` does, but `RoutingFilter` and `ConstantFilter` (the base of
`AcceptAllFilter`/`NeutralFilter`) implement `Filter` directly on top of `AbstractLifeCycle`.

### Plugin factory conventions

`@PluginFactory` methods follow these conventions:

- **Optional attributes** with a sensible default (e.g. `onMatch`, `level`) receive an
  explicit null-fallback inline: `onMatch == null ? Result.NEUTRAL : onMatch`.
- **Mandatory attributes** (e.g. `interval`, `timeUnit`, `maxEvents`) are *not* null-checked.
  If a caller omits them, the resulting NPE — surfaced by Log4j2 via its status logger — is
  considered descriptive enough. Do not add hand-written null checks or exceptions, and do
  not flag their absence in reviews.
- Log4j2's own declarative mechanisms — `@Required` and `@PluginAttribute(defaultInt=...)`
  etc., as used by `AsyncHttpAppender` — are equally fine; don't "normalise" one style into
  the other.

### AsyncHttpAppender

Invariants to be aware of when reading or reviewing the code:

- **Naming**: methods suffixed `AssumeLocked`/`AssumingLocked` must only be called with the
  appender's lock held.
- **One shared scheduler thread**: all async work — linger flushes, batch draining, retry
  backoff, and the `HttpClient`'s own executor and callbacks — intentionally runs on a
  single-thread `ScheduledThreadPoolExecutor` owned by `HttpClientManager`. The manager is
  shared between appenders with identical client settings, so nothing that runs on this
  thread may block.
- **`drainBufferedBatches` runs under the lock**: the method body is wrapped in `doWithLock`,
  but the HTTP calls are submitted as `CompletableFuture`s and complete outside the lock. The
  `whenComplete` callback re-acquires the lock explicitly when it needs to update shared
  state.
- **`currentBatchBytes` accounting**: tracks the exact serialized size of the in-progress
  batch (prefix + events + only those separators that `createBatchAssumingLocked` would
  actually write under the configured `batchSeparatorInsertionStrategy`). The `append` paths
  and `needsFlushAssumeLocked` consult `needSeparatorAssumeLocked` so the accounting and the
  flush-fits-check both match `createBatchAssumingLocked`'s layout exactly under both
  `ALWAYS` and `IF_MISSING`.
- **Linger anchor**: `firstRecordNanos` and `scheduledFlush` are reset together via
  `rescheduleFlushAssumeLocked` — call it from any new code path that empties `currentBatch`
  and lets a new batch start.
- **Never null `scheduledFlush` on task completion** — only the shutdown path does. A stale
  fire nulling a freshly-rescheduled future would defeat the next `cancel(false)`.

### Filters

- **ThrottlingFilter** — Rate-limits log events. The `level` attribute is easy to misread:
  events *at or less specific* than the configured level (e.g. WARN/INFO/DEBUG/TRACE when
  `level=WARN`) are counted against the limit; events *more specific* (e.g. ERROR/FATAL when
  `level=WARN`) bypass the throttle entirely and always return `onMatch`. This matches
  `BurstFilter` semantics. Performance-sensitive: avoid adding allocations or contention on
  the filter path, and back changes with the JMH benchmarks (see below).
- **RoutingFilter** — `getOnMatch()`/`getOnMismatch()` return `NEUTRAL`; routing decisions
  are delegated to each route's own filter.
- **AcceptAllFilter / NeutralFilter** — Always return `ACCEPT`/`NEUTRAL`; complement the
  mainline `DenyAllFilter`.

## Testing Approach

- **XML configs**: tests load Log4j2 configurations from `core/src/test/resources/`, named
  after the test class with an optional scenario suffix: `<TestClass>[.<scenario>].xml`
  (e.g. `ThrottlingFilterTest.atLogger.allowFew.xml`). They are loaded via
  `@LoggerContextSource` or `TestHelpers.loggerContextFromTestResource(...)`. Follow the
  same naming for new configs. `log4j2-test.xml` is the default config for all other tests.
- **Timeouts**: `parent/pom.xml` configures JUnit default timeouts of 2 minutes per test and
  30 seconds per lifecycle method (with thread dumps on timeout, disabled when debugging),
  plus a 30 minute limit for the forked Surefire JVM.
- **WireMock 3.x** is used for HTTP integration tests (AsyncHttpAppender).
- **`AsyncHttpAppenderTest` path isolation**: `beforeEach` derives a unique `testId` from a
  sequence counter and a unique WireMock path (`"/logs" + testId`) from it, so that
  stub/verify calls in one test cannot interfere with another. It exports `testId`,
  `wireMockHttpUrl` and `wireMockHttpsUrl` as system properties, which the XML configs
  consume via `${sys:...}`. Follow the same pattern in new tests.
- **AssertJ + `Configuration.getAppender()`**: the method's unconstrained generic return
  type (`<T extends Appender> T`) creates an ambiguous overload for AssertJ's `assertThat()`.
  Always cast explicitly: `assertThat((Appender) config.getAppender("name"))`.
- **Benchmarks and demos** live under `core/src/test/java/.../benchmarks/` and
  `.../demos/`. They are run manually, not as part of the test suite. JMH benchmarks start
  from `JmhMain` (adjust its hard-coded `.include` pattern) or from their own `main`;
  `AsyncHttpAppenderNonJmhBenchmarks` and the demos are plain `main` programs. The ingest
  demos and the non-JMH benchmarks talk to real backends: their XML configs read
  credentials via `${env:...}` (e.g. `DATADOG_API_KEY`, `DYNATRACE_API_TOKEN`,
  `GRAFANA_LOKI_TOKEN`).
- **Flaky tests**: use `scripts/find-flaky-tests.sh`; see
  [scripts/CLAUDE.md](scripts/CLAUDE.md).
