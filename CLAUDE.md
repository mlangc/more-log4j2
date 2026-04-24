# Guidance to agentic coding tools when working with code in this repository

## Project Overview

A collection of advanced plugins for Apache Log4j2 (2.25.4+): filters, appenders, and testing utilities. Published to Maven Central as `com.github.mlangc:more-log4j2`. Requires Java 17+.

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

The most complex component (~1034 LOC). Key design points:
- Asynchronous batching with configurable batch sizes and linger time
- Single-threaded batch draining using Java NIO `HttpClient`
- Ring buffer absorbs traffic spikes
- Retry logic with exponential backoff
- Optional gzip compression, custom headers, overflow strategies
- `BatchCompletionListener` for monitoring (avoid recursive logging — see README)

### Filters

- **ThrottlingFilter** — Rate-limits log events; performance-sensitive, benchmarked with JMH
- **RoutingFilter** — Routes events based on configurable conditions
- **AcceptAllFilter / NeutralFilter** — Simple accept/neutral implementations

### LogCaptor API

Captures log events in tests. Modeled after the `log-captor` library pattern.

## Testing Approach

- Unit tests use XML log4j2 configuration files under `core/src/test/resources/` — there are 70+ such files
- WireMock 3.x for HTTP integration tests (AsyncHttpAppender)
- JMH benchmarks in `src/test/java/.../benchmarks/` for performance-sensitive filters