# more-log4j2

[![Maven Central](https://img.shields.io/maven-central/v/com.github.mlangc/more-log4j2.svg?label=Maven%20Central)](https://search.maven.org/artifact/com.github.mlangc/more-log4j2)
[![Java 17 or higher](https://img.shields.io/badge/Java-17%2B-007396)](https://docs.oracle.com/javase/17/)

A collection of plugins for log4j2.

## Installation

This library is available [on Maven Central](https://central.sonatype.com/artifact/com.github.mlangc/more-log4j2?smo=true) under
the coordinates `com.github.mlangc:more-log4j2`. It requires only log4j2 and at least Java 17.

## Overview

* Filters
    * [RoutingFilter](#Routing-Filter)
    * [ThrottlingFilter](#Throttling-Filter)
    * [AcceptAllFilter](#AcceptAllFilter--NeutralFilter)
    * [NeutralFilter](#AcceptAllFilter--NeutralFilter)
* Appenders
    * [AsyncHttpAppender](#Async-HttpAppender)
    * [CapturingAppender](#Capturing-Appender)
    * [NullAppender](#Null-Appender)
* Maven Coordinates
    * [more-log4j2](#more-log4j2)
    * [more-log4j2-junit](#more-log4j2-junit)
    * [more-log4j2-bom](#more-log4j2-bom)

### Routing Filter

I'd like to explain `RoutingFilter` by illustrating how it can solve two use cases, that go beyond what you can do with standard
log4j2, unless you fall back to using [ScriptFilter](https://logging.apache.org/log4j/2.x/manual/filters.html#Script), or write a
plugin, as I did.

#### Use Case I: Marker Based Log Throttling

After defining a global filter, like

```xml

<Configuration status="warn">
    <RoutingFilter>
        <FilterRoute>
            <FilterRouteIf>
                <MarkerFilter marker="throttled10" onMatch="ACCEPT"/>
            </FilterRouteIf>
            <FilterRouteThen>
                <BurstFilter rate="10"/>
            </FilterRouteThen>
        </FilterRoute>

        <FilterRoute>
            <FilterRouteIf>
                <MarkerFilter marker="throttled1" onMatch="ACCEPT"/>
            </FilterRouteIf>
            <FilterRouteThen>
                <BurstFilter rate="1"/>
            </FilterRouteThen>
        </FilterRoute>

        <DefaultFilterRoute>
            <NeutralFilter/>
        </DefaultFilterRoute>
    </RoutingFilter>
    <!-- ... -->
    <!-- ... -->
    <!-- ... -->
</Configuration>
```

you can use marker based log throttling as follows:

```java
// ...
public static final Marker THROTTLED_1 = MarkerFactory.getMarker("throttled1");
public static final Marker THROTTLED_10 = MarkerFactory.getMarker("throttled10");

// ...
void anywhere() {
    LOG.info(THROTTLED_1, "Throttled to 1 log per sec");
    LOG.info(THROTTLED_10, "Throttled to 10 logs per sec");
    LOG.info("Not throttled at all");
}
```

#### Use Case II: Throttling DEBUG Logs

Let's imagine that you want to enable `DEBUG` or `TRACE` logs for parts your application or library code. At the same time, you
want to be on the safe side, and reliably avoid log spam. Then `RoutingFilter` can help you as follows:

```xml

<Configuration status="warn">
    <RoutingFilter>
        <FilterRoute>
            <FilterRouteIf>
                <ThresholdFilter level="info" onMatch="ACCEPT"/>
            </FilterRouteIf>
            <FilterRouteThen>
                <!-- No special handling for INFO and above -->
                <NeutralFilter/>
            </FilterRouteThen>
        </FilterRoute>

        <DefaultFilterRoute>
            <!-- DEBUG and TRACE logs are handled here -->
            <BurstFilter rate="1"/>
        </DefaultFilterRoute>
    </RoutingFilter>
    <!-- ... -->
    <!-- ... -->
    <!-- ... -->
</Configuration>
```

#### RoutingFilter Reference Documentation

`RoutingFilter` has no attributes, and is configured by its nested `FilterRoute` and `DefaultFilterRoute` elements:
Each `FilterRoute` must contain two child elements, `FilterRouteIf` and `FilterRouteThen` and both of them must contain filters
themselves. If the nested filter in `FilterRouteIf` returns `ACCEPT` (note that `NEUTRAL` is not enough), the filter branch in
`FilterRouteThen` is taken and all remaining `FilterRoute` elements, as well as the mandatory `DefaultFilterRoute`
are skipped. If no `FilterRoute` matches, the filters in `DefaultFilterRoute` are applied.

In Java, the behavior of the filter can be summarized as follows:

```java
void routingFilter() {
    for (var route : routes) {
        if (route.accepts(event)) {
            route.apply(event);
            return;
        }
    }

    defaultRoute.apply(event);
}
```

Whenever you are free to reorder `FilterRoute` elements because their matching sets don't overlap, I'd suggest to use the order
that makes your config the most readable. Putting the most commonly taken routes first might save you a few CPU cycles, however,
apart from extreme cases, where millions of logs are filtered down to a handful of lines every second, this won't make any
difference.

### AcceptAllFilter & NeutralFilter

These two filters don't have any options, and always return either `ACCEPT` or `NEUTRAL`. They complement
[DenyAllFilter](https://logging.apache.org/log4j/2.x/manual/filters.html#deny-filter), which exists in mainline log4j2, and are
especially useful in connection with `RoutingFilter`.

## Throttling Filter

`ThrottlingFilter` is an alternative to [BurstFilter](https://logging.apache.org/log4j/2.x/manual/filters.html#BurstFilter), that
provides roughly the same functionality, but with less overhead and without object allocations. Let's look at a few examples:

#### Throttling WARN and below to at most one log per second:

```xml

<ThrottlingFilter interval="1" timeUnit="SECONDS" maxEvents="1"/>
```

This is roughly what you get with

```xml

<BurstFilter rate="1" maxBurst="1"/>
```

#### Throttling DEBUG to at most 1 log per second, but allowing bursts up to 10

```xml

<ThrottlingFilter interval="10" timeUnit="SECONDS" maxEvents="10" level="debug"/>
```

This is roughly what you get with

```xml

<BurstFilter rate="1" maxBurst="10" level="debug"/>
```

#### ThrottlingFilter Configuration

| Attribute | Type                           | Default | Description                                                     |
|-----------|--------------------------------|---------|-----------------------------------------------------------------|
| interval  | long                           | -       | The throttling interval.                                        |
| timeUnit  | java.util.concurrent.TimeUnit  | -       | The time unit of the throttling interval                        |
| maxEvents | long                           | -       | Maximal number of log events per interval                       |
| level     | org.apache.logging.log4j.Level | WARN    | Only log events up until and including this level are throttled |

Conceptually, `ThrottlingFilter` divides the timeline into fixed intervals according to the `interval` configuration above, and
allows at most `maxEvents` logs in each interval. Note that this is subtly different from the `BurstFilter`, which maintains a
sliding window of length `maxBurst / rate` seconds, and allows at most `maxBurst` logs in this window. For most practical
purposes, this difference should be negligible.

#### Performance Comparison to BurstFilter

The most important performance related difference between `BurstFilter` and `ThrottlingFilter`, is that the latter is garbage free
during steady state logging (see [here](https://logging.apache.org/log4j/2.x/manual/garbagefree.html#Filters) for mainline log4j2
filters, that share this property). The overhead incurred by the `ThrottlingFilter`, apart from a call to
`System.nanoTime`, is dominated by an atomic `incrementAndGet` for logs that are not throttled, and two volatile reads for logs
that are throttled. This makes it extremely lightweight.

`BurstFilter`, as it is currently implemented, calls both `DelayQueue.poll` and `ConcurrentLinkedQueue.poll` at least once for
every invocation, which implies calls to `System.nanoTime` and some locking. In addition, the implementation moves
`LogDelay` objects between a `DelayQueue` and a `ConcurrentLinkedQueue`, which causes the allocation of queue nodes.

If you are interested in the details, please consider to take a look at the existing JMH benchmarks you find in this
repository.

Finally, it's important to keep things in perspective: None of this matters, unless millions of logs are filtered out per second,
or you are extremely sensitive about allocations.

### Async HttpAppender

A high throughput asynchronous HTTP appender, that supports batching and compression. It can be used to publish logs to popular
log monitoring solutions, like [Dynatrace](#Publishing-Logs-To-Dynatrace), [Datadog](#Publishing-Logs-To-Datadog) and
[Grafana](#Publishing-Logs-To-Grafana) and is able to deliver considerable log volumes with very little overhead.

#### Overall Architecture

In order to better understand the impact of the various configuration options the appender offers, it is useful to have a brief
look at its overall architecture:

![AsyncHttpAppender Archtecture](/doc/diagrams/AsyncHttpAppenderArchitecture.png)

Logs are always appended to the current batch. If the batch is full, or `lingerMs` elapsed, it is pushed to the batch buffer, and
a job to drain the buffer is scheduled. Buffered batches are handled in a FIFO manner, and pushed to the configured HTTP backend.

Draining of the batch buffer happens asynchronously, and is accomplished by a single thead, that uses the asynchronous API of
the [java.net.http.HttpClient](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html).

The purpose of the batch buffer is to absorb short log bursts, and backend hiccups. It is expected to be empty or almost empty
under normal conditions.

#### What if the backend is not keeping up?

If the backend is not keeping up, unsent batches accumulate in the batch buffer, which is limited by the
`maxBatchBufferBatches` and `maxBatchBufferBytes` options. If the buffer overflows, you have 3 options:

1. The log event that cannot be accommodated is dropped. This is the default behavior, since it's the safest strategy assuming
   that logs are a secondary concern of your application.
2. The appender blocks, until enough space is available. You can use the `maxBlockOnOverflowMs` option to limit the time you want
   to wait. Though this might sound like a good idea, be aware that even
   `maxBlockOnOverflowMs=1` might seriously degrade the performance of your application, if your backend becomes unreachable.
3. The log event that cannot be handled is forwarded to an "overflow appender". You can configure such an appender by using a
   nested `OverflowAppenderRef`. Note that only events that cannot be enqueued are forwarded to this appender; the log events in
   the already queued batches will never be forwarded to the overflow appender.

#### What if the backend is unreliable, or temporary unavailable?

The `AsyncHttpAppender` implements retries with randomized, exponential backoff. You can control retry behavior using these
options: `retries`, `maxBackoffMs`, `httpRetryCodes` and `retryOnIoError`.

If a batch cannot be delivered to the configured backend after having exhausted its retry budget, it is dropped, and all log
events in this batch are lost. This will be accompanied by a warning being logged to the
[StatusLogger](https://logging.apache.org/log4j/2.x/manual/status-logger.html) by default. You can get a notification if this
happens by configuring a custom `com.github.mlangc.more.log4j2.appenders.AsyncHttpAppender.BatchCompletionListener` using the
`batchCompletionListener` option for monitoring purposes.

#### Will I loose logs on shutdown?

Depending on your setup you might want to set
[log4j2.shutdownHookEnabled](https://logging.apache.org/log4j/2.x/manual/systemproperties.html#log4j2.shutdownHookEnabled)
to true, to avoid loosing logs on regular shutdowns. `log4j2.shutdownHookEnabled` defaults to false if a `Servlet` class is found
on your classpath (see
[log4j2.isWebapp](https://logging.apache.org/log4j/2.x/manual/systemproperties.html#log4j2.isWebapp)). You can configure how long
the appender should wait for batches to be pushed to the backend by setting the `shutdownTimeoutMs` option.

#### I've set `log4j2.shutdownHookEnabled`, but I'm still loosing some logs when my tests are shutdown.

This might be because Spring
uses [a custom property source](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/logging/log4j2/SpringBootPropertySource.html)
that disables the log4j2 shutdown hook.

To make sure that the appender is flushed whenever your junit test suite is terminated, add
`com.github.mlangc:more-log4j2-junit` as a test dependency. This installs a custom 
[TestExecutionListener](https://docs.junit.org/6.0.1/api/org.junit.platform.launcher/org/junit/platform/launcher/TestExecutionListener.html)
via the [ServiceLoader facility](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/ServiceLoader.html).

#### Will I loose logs if the application crashes?

Yes, there is no way an asynchronous appender can make sure that buffers are drained if the application crashes. However, there
are some configuration options that have an influence on the amount of logs that might be lost:

* Setting `lingerMs`, `maxBatchBytes` and `maxBatchLogEvents` to smaller values will push batches to the backend more quickly. The
  downsides are more HTTP requests and potentially worse compression rates.
* Setting `maxBatchBufferBatches` and `maxBatchBufferBytes` to smaller values will limit the amount of buffered batches, that
  accumulate if the backend is not keeping up. On the other hand, a small batch buffer might not be able to absorb short spikes in
  log volumes, or brief hickups of the backend.

However, if you are worrying about lost logs in the event of an application crash, `AsyncHttpAppender` is probably [not the 
right tool for your use case](#should-i-use-the-asynchttpappender-in-production).

#### Should I use the AsyncHttpAppender in production?

Most likely not, since ingesting logs from within the logging application itself has some drawbacks, that even the best possible
appender implementation cannot mitigate. Log aggregation and forwarding in production like setups is typically addressed by
dedicated components, that collect, enrich, aggregate and forward logs from multiple applications.

However, setting up this kind of infrastructure on your local machine is overkill, and might not be possible.

#### Performance

In principle, the `AsyncHttpAppender` can sustain throughputs similar to a file appender, in the ballpark of millions of log
events per second, if the configured HTTP backend is able to absorb the traffic fast enough.

In tests that I performed (see `AsyncHttpAppenderNonJmhBenchmarks` if you are interested in the details) with the ingest APIs of
Dynatrace, Datadog and Grafana, I was able to sustain rates approaching 50_000 log events per second corresponding to ~18MB/s of
data from my laptop. Since compression ratios around 20 are quite common for logs, especially if they are enriched with constant
attributes like hostnames, service names and so on, setting
`contentEncoding="gzip"` is recommended, since this trades a small amount of CPU time for a significant reduction in network
traffic.

#### Memory Usage & Churn

Memory usage of the appender is controlled by `maxBatchBufferBytes`, which defaults to `50 * maxBatchBytes + 250_000`. My advice
is to not tweak `maxBatchBufferBytes`, but only `maxBatchBytes`.

The churn is dominated by temporary byte arrays that are used as a backing storage for rendered log events and batches. It should
be possible to reduce it significantly, however I decided to keep that for a future release.

#### Publishing Logs to Dynatrace

To publish logs to the
[Dynatrace Log Monitoring API v2](https://docs.dynatrace.com/docs/discover-dynatrace/references/dynatrace-api/environment-api/log-monitoring-v2/post-ingest-logs)
you can use the following recipe:

* Add a dependency to the
  [JSON template layout](https://logging.apache.org/log4j/2.x/manual/json-template-layout.html)
  that is part of mainline log4j2, but is shipped in a separate JAR.
* Create a layout file to format your log messages that looks like
  ```json
  {
    "timestamp": {
      "$resolver": "timestamp",
      "epoch": {
        "unit": "millis",
        "rounded": true
      }
    },
    "level": {
      "$resolver": "level",
      "field": "name"
    }
  }
  ```
  and place it on your classpath.
* Now you can copy and paste from the following log4j2 configuration according to your needs:
   ```xml
  <Configuration status="WARN">
    <Properties>
        <Property name="pattern" value="%d{HH:mm:ss.SSS} %-5level %logger{1} - %msg%n"/>
    </Properties>
    <Appenders>

        <!--
            DYNATRACE_API_V2_LOGS_INGEST should point the ingest V2 API as outlined in
            https://docs.dynatrace.com/docs/discover-dynatrace/references/dynatrace-api/environment-api/log-monitoring-v2/post-ingest-logs.

            DYNATRACE_API_TOKEN needs to point to a valid API token.
            See https://docs.dynatrace.com/docs/discover-dynatrace/references/dynatrace-api/basics/dynatrace-api-authentication.
        -->
        <AsyncHttp name="Dynatrace" url="${env:DYNATRACE_API_V2_LOGS_INGEST}"
                   maxBatchBytes="500000" maxBatchLogEvents="50000"
                   httpSuccessCodes="200,204" httpRetryCodes="429,503"
                   contentEncoding="gzip">
            <Property name="Authorization" value="Api-Token ${env:DYNATRACE_API_TOKEN}"/>
            <Property name="Content-Type" value="application/jsonl"/>

            <JsonTemplateLayout eventTemplateUri="classpath:DynatraceLogMessageJsonLayout.json">
                <!--
                    This can also be embedded in the layout directly, but doing it like this
                    has the advantage that the pattern can be substituted.
                    See https://logging.apache.org/log4j/2.x/manual/json-template-layout.html#property-substitution-in-config
                -->
                <EventTemplateAdditionalField
                        key="message"
                        format="JSON"
                        value='{"$resolver": "pattern", "pattern": "${pattern}"}'/>

                <!-- Some random attributes to demonstrate how easy it is to add additional fields -->
                <EventTemplateAdditionalField key="dt.os.type" value="${java:os}"/>
                <EventTemplateAdditionalField key="java.runtime" value="${java:runtime}"/>
            </JsonTemplateLayout>
        </AsyncHttp>

        <Console name="Console">
            <PatternLayout pattern="${pattern}"/>
        </Console>
    </Appenders>
    <Loggers>
        <Root level="info">
            <!-- This will push logs to Dynatrace and to the console -->
            <AppenderRef ref="Console"/>
            <AppenderRef ref="Dynatrace"/>
        </Root>
    </Loggers>
  </Configuration>
  ```

#### Publishing Logs to Datadog

To publish logs to the
[Datadog V2 Log Ingest API](https://docs.datadoghq.com/api/latest/logs/)
you can use the following recipe:

* Add a dependency to the
  [JSON template layout](https://logging.apache.org/log4j/2.x/manual/json-template-layout.html)
  that is part of mainline log4j2, but is shipped in a separate JAR.
* Create a layout file to format your log messages that looks like
  ```json
  {
    "status": {
      "$resolver": "level",
      "field": "name"
    }
  }
  ```
  and place it on your classpath.
* Now you can copy and paste from the following log4j2 configuration according to your needs:
  ```xml
  <Configuration status="WARN">
    <Properties>
      <Property name="pattern" value="%d{HH:mm:ss.SSS} %-5level %logger{1} - %msg%n"/>
    </Properties>
    <Appenders>

      <!--
          The url needs to be adapted according to your region.
          See https://docs.datadoghq.com/api/latest/logs/

          DATADOG_API_KEY should point to a valid Datadog API key.
          See https://docs.datadoghq.com/account_management/api-app-keys/
      -->
      <AsyncHttp name="Datadog" url="https://http-intake.logs.datadoghq.eu/api/v2/logs"
                 batchPrefix="[" batchSeparator="," batchSuffix="]"
                 maxBatchBytes="4500000" maxBatchLogEvents="1000"
                 httpSuccessCodes="202" httpRetryCodes="408,429,500,503">
        <Property name="DD-API-KEY" value="${env:DATADOG_API_KEY}"/>
        <Property name="Content-Type" value="application/json"/>
        <JsonTemplateLayout eventTemplateUri="classpath:DatadogLogMessageJsonLayout.json">
          <EventTemplateAdditionalField
                  key="message"
                  format="JSON"
                  value='{"$resolver": "pattern", "pattern": "${pattern}"}'/>

          <EventTemplateAdditionalField key="ddtags" value="env:demo"/>
          <EventTemplateAdditionalField key="ddsource" value="demo"/>
          <EventTemplateAdditionalField key="hostname" value="demo"/>
          <EventTemplateAdditionalField key="service" value="demo"/>
        </JsonTemplateLayout>
      </AsyncHttp>

      <Console name="Console">
        <PatternLayout pattern="${pattern}"/>
      </Console>
    </Appenders>
    <Loggers>
      <Root level="info">
        <!-- This will push logs to Datadog and to the console -->
        <AppenderRef ref="Datadog"/>
        <AppenderRef ref="Console"/>
      </Root>
    </Loggers>
  </Configuration>
  ```

#### Publishing Logs to Grafana

To publish logs to
[Grafana Loki](https://grafana.com/docs/loki/latest/reference/loki-http-api/#ingest-logs)
you can use the following recipe:

* Add a dependency to the
  [JSON template layout](https://logging.apache.org/log4j/2.x/manual/json-template-layout.html)
  that is part of mainline log4j2, but is shipped in a separate JAR.
* Create a layout file to format your log messages that looks like
  ```json
  {
    "stream": {
      "service": "test-app"
    },
    "values": [
      [
        {
          "$resolver": "pattern",
          "pattern": "%d{UNIX_MILLIS}000000"
        }, {
        "$resolver": "pattern",
        "pattern": "%d{HH:mm:ss.SSS} %-5level %c{2} - %msg%n"
      }
      ]
    ]
  }
  ```
  and place it on your classpath.
* Now you can copy and paste from the following log4j2 configuration according to your needs:
  ```xml
  <Configuration status="WARN">
    <Appenders>
      <!--
          The url needs to be adapted according to your setup. See https://grafana.com/docs/loki/latest/reference/loki-http-api/#ingest-logs and
          "Connections > Data sources" for details.

          GRAFANA_LOKI_TOKEN needs to point to a Grafana Token suitable for log ingest in the format used by Basic HTTP Authentication:
            "<userId>:<credentials>" converted to Base64. See https://en.wikipedia.org/wiki/Basic_access_authentication
      -->
      <AsyncHttp name="Grafana" url="https://logs-prod-012.grafana.net/loki/api/v1/push"
                 batchPrefix='{"streams": [' batchSeparator="," batchSuffix="]}"
                 maxBatchLogEvents="5000" maxBatchBytes="500000"
                 contentEncoding="gzip">
        <Property name="Content-Type" value="application/json"/>
        <Property name="Authorization" value="Basic ${env:GRAFANA_LOKI_TOKEN}"/>
        <JsonTemplateLayout eventTemplateUri="classpath:GrafanaLokiV1PushLogSingleMessageJsonLayout.json"/>
      </AsyncHttp>

      <Console name="Console">
        <PatternLayout pattern="%d{HH:mm:ss.SSS} %-5level %logger{1} - %msg%n"/>
      </Console>
    </Appenders>
    <Loggers>
      <Root level="info">
        <!-- Pushes logs to Grafana and to the console -->
        <AppenderRef ref="Grafana"/>
        <AppenderRef ref="Console"/>
      </Root>
    </Loggers>
  </Configuration>
  ```

#### Configuration

| Parameter                       | Type                                                | Default Value                  | Required | Description                                                                                                                                     | Expert Only (not to be tweaked unless for specific needs) |
|---------------------------------|-----------------------------------------------------|--------------------------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------|
| name                            | String                                              | -                              | Yes      | Appender name                                                                                                                                   | No                                                        |
| url                             | URI                                                 | -                              | Yes      | Target HTTP endpoint                                                                                                                            | No                                                        |
| layout (nested element)         | Layout                                              | -                              | Yes      | A layout to format log messages                                                                                                                 | No                                                        |
| lingerMs                        | int                                                 | 5000                           | No       | Max time to wait before sending a batch                                                                                                         | No                                                        |
| maxBlockOnOverflowMs            | int                                                 | 0                              | No       | Max time to block on overflow before dropping a log event. Configuring a negative value is equvalent to setting `Integer.MAX_VALUE`.            | No                                                        |
| maxBatchBytes                   | int                                                 | 250_000                        | No       | Max size of a batch (uncompressed). Can be set to `0` to disable batching.                                                                      | No                                                        |
| maxBatchLogEvents               | int                                                 | 1000                           | No       | Max number of log events per batch                                                                                                              | No                                                        |
| maxBatchBufferBatches           | int                                                 | 50                             | No       | Max number of batches to buffer (see also `maxBatchBufferBytes`)                                                                                | No                                                        |
| connectTimeoutMs                | int                                                 | 10_000                         | No       | HTTP connection timeout                                                                                                                         | No                                                        |
| readTimeoutMs                   | int                                                 | 10_000                         | No       | HTTP read timeout                                                                                                                               | No                                                        |
| shutdownTimeoutMs               | int                                                 | 15_000                         | No       | Max time to wait till buffers are drained on shutdown. Configuring a negative value is equvalent to setting `Integer.MAX_VALUE`.                | No                                                        |
| maxConcurrentRequests           | int                                                 | 5                              | No       | Max concurrent HTTP requests                                                                                                                    | No                                                        |
| method                          | "POST" or "PUT"                                     | "POST"                         | No       | HTTP method (POST/PUT)                                                                                                                          | No                                                        |
| batchPrefix                     | String                                              | ""                             | No       | Prefix for each batch                                                                                                                           | No                                                        |
| batchSeparator                  | String                                              | "\n"                           | No       | Separator between log events in a batch                                                                                                         | No                                                        |
| batchSuffix                     | String                                              | ""                             | No       | Suffix for each batch                                                                                                                           | No                                                        |
| httpSuccessCodes                | String                                              | 200, 202, 204                  | No       | HTTP status codes considered successful                                                                                                         | No                                                        |
| httpRetryCodes                  | String                                              | 429, 500, 502, 503, 504        | No       | HTTP status codes that trigger a retry                                                                                                          | No                                                        |
| retryOnIoError                  | boolean                                             | true                           | No       | Retry on I/O errors                                                                                                                             | No                                                        |
| retries                         | int                                                 | 5                              | No       | Number of (exponentially backed off) retry attempts                                                                                             | No                                                        |
| maxBackoffMs                    | int                                                 | 10_000                         | No       | Max backoff time to wait between retries                                                                                                        | No                                                        |
| contentEncoding                 | encoding (identity or gzip)                         | identity                       | No       | Batch content encoding (identity/gzip)                                                                                                          | No                                                        |
| filter (nested)                 | Filter                                              | -                              | No       | An optional filter                                                                                                                              | No                                                        |
| overflowAppenderRef (nested)    | String                                              | null                           | No       | An appender ref to which events are routed if the internal batch buffer is full (the implementation will first wait for `maxBlockOnOverflowMs`) | No                                                        |
| properties (nested)             | Property[]                                          | -                              | No       | Additional HTTP headers                                                                                                                         | No                                                        |
| httpClientSslConfigSupplier     | String                                              | null                           | No       | Class name for custom SSL config supplier                                                                                                       | No                                                        |
| batchSeparatorInsertionStrategy | separator insertion strategy (if_missing or always) | if_missing                     | No       | Strategy for inserting separators between events. `if_missing` means that separators are only inserted, if they are not already present.        | No                                                        |
| maxBatchBufferBytes             | int                                                 | `50 * maxBatchBytes + 250_000` | No       | Max total buffer size for batches (potentially compressed)                                                                                      | Yes                                                       |
| ignoreExceptions                | boolean                                             | true                           | No       | Ignore exceptions during logging                                                                                                                | Yes                                                       |
| batchCompletionListener         | String                                              | null                           | No       | Class name for custom batch completion listener                                                                                                 | Yes                                                       |

#### Read this if you want to play with the `batchCompletionListener` option

The `AsyncHttpAppender` can expose internal data for monitoring purposes to a user supplied
`com.github.mlangc.more.log4j2.appenders.AsyncHttpAppender.BatchCompletionListener` implementation. Though the interface is
simple, and hopefully self-explanatory, it is very easy to shoot yourself into the foot unless you read the following paragraph
carefully:

1. Your `BatchCompletionListener` will be executed by the thread that drains the batch buffer. This thread must never be blocked
   or monopolized for longer periods of time, or else your logs might not be drained in time.
2. Logs generated directly or indirectly from a `BatchCompletionListener` are problematic, since they lead to recursive
   invocations of the appender. If you must log, do so asynchronously, from another thread and ensure that the amount of logs that
   you generate in the listener is small compared to `maxBatchBytes` and
   `maxBatchLogEvents` to prevent feedback loops.

### Capturing Appender

The `CaptoringAppender` is meant to be used along with the `com.github.mlangc.more.log4j2.captor.LogCaptor` API, which is modeled
after the
[LogCaptor](https://github.com/Hakky54/log-captor) library. Unlike the latter, it works exclusively with `log4j2`, and doesn't
force you to switch to `logback` for your tests. To use it, first adapt your `log4j2-test.xml` according to the following example:
```xml
<Configuration status="WARN">
    <Appenders>
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="%d{HH:mm:ss.SSS} %-5level %c{2}:%markerSimpleName - %msg%n"/>
        </Console>

        <!-- Exactly one Captor is needed; the name does not matter-->
        <Captor name="Captor"/>
    </Appenders>
    <Loggers>
        <Root level="info">
            <AppenderRef ref="Console" />
            
            <!-- Don't forget to add the captor to the root logger; nothing is captured unless LogCaptors are created -->
            <AppenderRef ref="Captor"/>
        </Root>
    </Loggers>
</Configuration>
```

After having prepared your `log4j2-test.xml` like above, you can use the `com.github.mlangc.more.log4j2.captor.LogCaptor` API as
follows:
```java
public class LogCaptorDemoTest {
    private static final Logger LOG = LoggerFactory.getLogger(LogCaptorDemoTest.class);

    static class Service1 {
        private static final Logger LOG = LoggerFactory.getLogger(Service1.class);
    }

    static class Service2 {
        private static final Logger LOG = LoggerFactory.getLogger(Service2.class);
    }

    @AutoClose
    private final LogCaptor service1Captor = LogCaptor.forClass(Service1.class);

    @AutoClose
    private final LogCaptor service2Captor = LogCaptor.forClass(Service2.class);

    @Test
    void shouldNotCaptureAnythingIfNothingHappens() {
        assertThat(service1Captor.getLogs()).isEmpty();
        assertThat(service2Captor.getLogs()).isEmpty();
    }

    @Test
    void shouldCaptureInfoLogs() {
        Service1.LOG.info("Hello service 1");
        Service2.LOG.info("Hello service 2");

        assertThat(service1Captor.getInfoLogs()).containsExactly("Hello service 1");
        assertThat(service2Captor.getInfoLogs()).containsExactly("Hello service 2");
    }

    @Test
    void shouldCaptureInfoAndWarnAndErrorLogs() {
        Service1.LOG.info("Info");
        Service1.LOG.warn("Warn");
        Service1.LOG.error("Error");

        assertThat(service1Captor.getInfoLogs()).containsExactly("Info");
        assertThat(service1Captor.getWarnLogs()).containsExactly("Warn");
        assertThat(service1Captor.getErrorLogs()).containsExactly("Error");
        assertThat(service1Captor.getLogs()).containsExactly("Info", "Warn", "Error");
    }

    @Test
    void shouldCaptureExceptions() {
        Service1.LOG.warn("Ups", new RuntimeException("darn"));

        assertThat(service1Captor.getLogEvents()).hasSize(1).first()
                .satisfies(evt -> assertThat(evt.getThrown()).hasMessage("darn"));
    }

    @Test
    void shouldCaptureMdc() {
        try (var ignore = MDC.putCloseable("test", "me")) {
            Service1.LOG.info("Test");
        }

        assertThat(service1Captor.getLogEvents()).hasSize(1).first()
                .satisfies(evt -> assertThat(evt.getContextData().toMap()).containsExactly(Map.entry("test", "me")));
    }

    @Test
    void shouldNotCaptureDebugLogsUnlessEnabled() {
        Service1.LOG.debug("Not captured");
        assertThat(service1Captor.getDebugLogs()).isEqualTo(service1Captor.getLogs()).isEmpty();

        service1Captor.setLogLevelToDebug();
        Service1.LOG.debug("Captured");

        assertThat(service1Captor.getDebugLogs())
                .isEqualTo(service1Captor.getLogs())
                .containsExactly("Captured");
    }

    @Test
    void shouldClearLogs() {
        Service1.LOG.info("Test 1");
        assertThat(service1Captor.getInfoLogs()).hasSize(1);

        service1Captor.clearLogs();
        assertThat(service1Captor.getInfoLogs()).isEmpty();

        Service1.LOG.info("Test 2");
        assertThat(service1Captor.getInfoLogs()).hasSize(1);

        service1Captor.clearLogs();
        assertThat(service1Captor.getInfoLogs()).isEmpty();
    }

    @Test
    void shouldNotCaptureLogsWhileDisabled() {
        service1Captor.disableLogs();
        Service1.LOG.info("Nada");
        assertThat(service1Captor.getLogs()).isEmpty();

        service1Captor.resetLogLevel();
        Service1.LOG.info("Tada");
        assertThat(service1Captor.getLogs()).containsExactly("Tada");
    }

    // Use this with care, since captured logs are kept in memory till the captor is closed
    @AutoClose
    private final LogCaptor rootCaptor = LogCaptor.forRoot();

    @Test
    void rootCaptorShouldCaptureEverything() {
        LOG.info("Hello 0");
        Service1.LOG.info("Hello 1");
        Service2.LOG.info("Hello 2");

        assertThat(rootCaptor.getInfoLogs()).containsExactly("Hello 0", "Hello 1", "Hello 2");
        assertThat(service1Captor.getInfoLogs()).containsExactly("Hello 1");
        assertThat(service2Captor.getInfoLogs()).containsExactly("Hello 2");
    }

    @Test
    void baseCaptorShouldCaptureLogsFromBothServices() {
        try (var baseCaptor = LogCaptor.forName("com.github.mlangc.more.log4j2")) {
            Service1.LOG.info("Howdy");
            Service1.LOG.info("Hello");

            assertThat(baseCaptor.getInfoLogs()).containsExactly("Howdy", "Hello");
        }
    }

    @Test
    void shouldCaptureLogsFromOtherThread() {
        CompletableFuture.runAsync(() -> Service1.LOG.info("async")).join();
        assertThat(service1Captor.getInfoLogs()).containsExactly("async");
    }
}
```

### Null Appender

The `NullAppender` is the Log4j2 equivalent of `/dev/null`: It silently discards all logs sent to it.

It can be used like

```xml

<Null name="Null"/>
```

and can be useful in connection with an [arbiter](https://logging.apache.org/log4j/2.x/manual/configuration.html#arbiters), for
example like this:

```xml
<!-- 
  Forwards logs to Dynatrace using the AsyncHttpAppender if the environment variable DYNATRACE_API_V2_LOGS_INGEST is 
  present
 -->
<Appenders>
    <Select>
        <EnvironmentArbiter propertyName="DYNATRACE_API_V2_LOGS_INGEST">
            <AsyncHttp name="Dynatrace" url="${env:DYNATRACE_API_V2_LOGS_INGEST}"
                       maxBatchBytes="500000" maxBatchLogEvents="50000"
                       httpSuccessCodes="200,204" httpRetryCodes="429,503"
                       contentEncoding="gzip">
                <!-- ... -->
                <!-- ... -->
                <!-- ... -->
            </AsyncHttp>
        </EnvironmentArbiter>
        <DefaultArbiter>
            <Null name="Dynatrace"/>
        </DefaultArbiter>
    </Select>
</Appenders>
<Loggers>
<Root level="info">
    <AppenderRef ref="Console"/>
    <AppenderRef ref="Dynatrace"/>
</Root>
</Loggers>
```

## Maven Coordinates

### more-log4j2

`com.github.mlangc:more-log4j2` is the main module. It contains filters, appenders and the `LogCaptor`.

### more-log4j2-junit

`com.github.mlangc:more-log4j2-junit` contains a
[junit TestExecutionListener](https://docs.junit.org/6.0.0/api/org.junit.platform.launcher/org/junit/platform/launcher/TestExecutionListener.html)
that makes sure `AsyncHttpAppender`s are flushed after your tests have finished regardless of the
[log4j2.shutdownHookEnabled system property](https://logging.apache.org/log4j/2.x/manual/systemproperties.html#log4j2.shutdownHookEnabled),
that might have no effect
[due to on override from spring boot](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/logging/log4j2/SpringBootPropertySource.html).
Simply putting this artifact into your test runtime classpath (`testRuntimeOnly` with Gradle) is enough. The
`AsyncHttpAppenderFlushingTestExecutionListener` is [registered automatically via the ServiceLoader mechanism](https://docs.junit.org/6.0.1/advanced-topics/launcher-api.html#listeners-custom).

### more-log4j2-bom
`com.github.mlangc:more-log4j2-bom` contains a 
[BOM](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Bill_of_Materials_.28BOM.29_POMs)
with module versions. If you use more than one `more-log4j2` module, you can use this BOM to make sure that their versions are
compatible.

## Additional Notes

### Why not extend log4j2 directly?

My plan is to migrate the most useful parts of this library to mainline log4j2 at some point. However, log4j2 accepts new plugins
only if they have demonstrated long-term stability and have a broad user base. Please
see [this discussion](https://github.com/apache/logging-log4j2/discussions/3976) for details.

### License

This project is licensed under the Apache License 2.0.
