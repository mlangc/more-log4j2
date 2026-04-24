# Guidance to agentic coding tools when working with code in this repository

## Project Overview

A collection of advanced plugins for Apache Log4j2: filters, appenders, and testing utilities. Published to Maven Central as `com.github.mlangc:more-log4j2`. Requires Java 17+.

## Build Commands

Uses Maven Wrapper — prefer `./mvnw` over `mvn`:

```bash
# Build and test all modules
./mvnw -B package

# Run all tests
./mvnw test

# Run a single test class (use -pl to avoid failure in modules that don't have the test)
./mvnw -Dtest=ClassName test -pl core -am

# Mutation testing (PIT) — always run selectively; full suite can take 30+ minutes.
# Requires compiled classes first. Use -DtargetClasses with a glob to scope to one class or package:
./mvnw test-compile -pl core -am -q && ./mvnw org.pitest:pitest-maven:mutationCoverage -pl core -am -DtargetClasses='com.github.mlangc.more.log4j2.filters.ThrottlingFilter'

# Update license headers
./mvnw org.codehaus.mojo:license-maven-plugin:update-file-header
```

JaCoCo code coverage runs automatically with `./mvnw test`.

## Module Structure

- **`core/`** — All filters, appenders, and the LogCaptor API
- **`junit/`** — `AsyncHttpAppenderFlushingTestExecutionListener` for test integration
- **`parent/`** — Shared POM configuration and dependency versions
- **`bom/`** — Bill of Materials for consumers using multiple modules

## Architecture

### Plugin System

All components use Log4j2's `@Plugin` annotation and are auto-discovered via the Log4j2 plugin annotation processor. Filters extend `AbstractFilter`; appenders extend `AbstractAppender`. Configuration maps to XML element names via `@Plugin(name=...)`.

### AsyncHttpAppender

The most complex component (~1034 LOC). See [README.MD](README.MD) for the full
configuration reference and architecture diagram. The key implementation detail to be aware
of when reading the code: batch draining is intentionally **single-threaded** (one drainer
thread using the async `HttpClient` API), so any logic that touches the drain path must not
block.

### Filters

See [README.MD](README.MD) for full configuration reference and usage examples.

- **ThrottlingFilter** — Rate-limits log events; performance-sensitive, benchmarked with
  JMH (benchmarks live under `src/test/java/.../benchmarks/`).
  The `level` attribute is easy to misread: events *at or less specific* than the configured
  level (e.g. WARN/INFO/DEBUG/TRACE when `level=WARN`) are counted against the limit;
  events *more specific* (e.g. ERROR/FATAL when `level=WARN`) bypass the throttle entirely
  and always return `onMatch`. This matches `BurstFilter` semantics.
- **RoutingFilter** — Evaluates `FilterRoute` entries in order; the first whose
  `FilterRouteIf` filter returns `ACCEPT` wins and its `FilterRouteThen` filter is applied.
  Falls back to `DefaultFilterRoute`. `getOnMatch()`/`getOnMismatch()` intentionally throw
  `UnsupportedOperationException`.
- **AcceptAllFilter / NeutralFilter** — Always return `ACCEPT`/`NEUTRAL`; complement the
  mainline `DenyAllFilter`.

### Plugin factory conventions

`@PluginFactory` methods follow these conventions:

- **Optional attributes** with a sensible default (e.g. `onMatch`, `level`) receive an explicit null-fallback inline: `onMatch == null ? Result.NEUTRAL : onMatch`.
- **Mandatory attributes** (e.g. `interval`, `timeUnit`, `maxEvents`) are *not* null-checked. If a caller omits them, the resulting NPE — surfaced by Log4j2 via its status logger — is considered descriptive enough. Adding custom validation would be noise without diagnostic value.

### LogCaptor API

Captures log events in tests. See [README.MD](README.MD) for usage examples.

## Testing Approach

- Unit tests use XML log4j2 configuration files under `core/src/test/resources/` — there are 70+ such files
- WireMock 3.x for HTTP integration tests (AsyncHttpAppender)
- JMH benchmarks in `src/test/java/.../benchmarks/` for performance-sensitive filters
