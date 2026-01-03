# more-log4j2

[![Maven Central](https://img.shields.io/maven-central/v/com.github.mlangc/more-log4j2.svg?label=Maven%20Central)](https://search.maven.org/artifact/com.github.mlangc/more-log4j2)
[![Java 8 or higher](https://img.shields.io/badge/JDK-8%2B-007396)](https://docs.oracle.com/javase/8/)

A collection of plugins for log4j2.

## Installation

This library is available [on Maven Central](https://central.sonatype.com/artifact/com.github.mlangc/more-log4j2?smo=true) under
the coordinates `com.github.mlangc:more-log4j2`. It requires only log4j2 and at least Java 8.

## Features

* Filters
    * [RoutingFilter](#Routing-Filter)
    * [ThrottlingFilter](#Throttling-Filter)
    * [AcceptAllFilter](#AcceptAllFilter--NeutralFilter)
    * [NeutralFilter](#AcceptAllFilter--NeutralFilter)
* Appenders
    * [AsyncHttpAppender](#Async-HttpAppender)
    * [NullAppender](#Null-Appender)

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

If you are interested in the details, please also consider to take a look at the existing JMH benchmarks you find in this
repository.

Finally, it's important to keep things in perspective: None of this matters, unless millions of logs are filtered out per second,
or you are extremely sensitive about allocations.

### Async HttpAppender

A high throughput asynchronous HTTP appender, that supports batching and compression. It can be used to publish logs to popular
log monitoring solutions, like Dynatrace, Datadog and Grafana and is able to deliver considerable log volumes with very little
overhead.

#### Overall Architecture

In order to better understand the impact of the various configuration options the appender offers, it is useful to have
a brief look at its overall architecture:

![AsyncHttpAppender Archtecture](/doc/diagrams/AsyncHttpAppenderArchitecture.png)

As you can see, logs are always appended to the current batch. If the batch is full, or `lingerMs` elapsed, it is pushed
to the batch buffer. Buffered batches are drained in a FIFO manner, and pushed to the configured HTTP backend.

Draining of the batch buffer happens asynchronously, and is accomplished by a single thead, that uses the asynchronous API
of the [java.net.http.HttpClient](https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html)

#### What if the backend is not keeping up?

If the backend is not keeping up, unsent batches accumulate in the batch buffer, which is limited by the
`maxBatchBufferBatches` and `maxBatchBufferBytes` options. If the buffer overflows, you have 3 options:

1. The log event that cannot be accommodated is dropped. This is the default behavior, since it's the safest strategy
   assuming that logs are a secondary concern of your application.
2. The appender blocks, until enough space is available. You can use the `maxBlockOnOverflowMs` option to limit the time you want
   to wait. Though this might sound like a good idea, be aware that even
   `maxBlockOnOverflowMs=1` might seriously degrade the performance of your application, if your backend becomes unreachable.
3. The log event that cannot be handled is forwarded to an "overflow appender". You can configure such an appender by using a
   nested `OverflowAppenderRef`. Note that only events that cannot be enqueued are forwarded to this appender; the log events in
   the already queued batches will never be forwarded to the overflow appender.

#### What if the backend is unreliable, or temporary unavailable?

The `AsyncHttpAppender` implements retries with randomized, exponential backoff. You can control retry behavior using these
options: `retries`, `httpRetryCodes` and `retryOnIoError`.

If a batch cannot be delivered to the configured backend after having exhausted its retry budget, it is dropped, and all log
events in this batch are lost. This will be accompanied by a warning being logged to the
[StatusLogger](https://logging.apache.org/log4j/2.x/manual/status-logger.html) by default. You can get a notification if this
happens by configuring a custom `com.github.mlangc.more.log4j2.appenders.AsyncHttpAppender.BatchCompletionListener` using the
`batchCompletionListener` option for monitoring purposes.

#### Attention

Depending on your setup you might want to set
[log4j2.shutdownHookEnabled](https://logging.apache.org/log4j/2.x/manual/systemproperties.html#log4j2.shutdownHookEnabled)
to true, to avoid loosing logs on regular shutdowns. `log4j2.shutdownHookEnabled` defaults to false if a `Servlet` class is found
on your classpath (see
[log4j2.isWebapp](https://logging.apache.org/log4j/2.x/manual/systemproperties.html#log4j2.isWebapp)).

#### Performance

In principle, the `AsyncHttpAppender` can sustain throughputs similar to a file appender, in the ballpark of millions of log
events per second, if the configured HTTP backend is able to absorb the traffic fast enough.

In tests that I performed with the ingest APIs of Dynatrace, Datadog and Grafana, I was able to sustain rates approaching 50_000
log events per second corresponding to ~18MB/s of data. Since compression ratios around 20 are quite common for logs, especially
if they are enriched with constant attributes like hostnames, service names and so on, setting
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
| contentEncoding                 | encoding (identity or gzip)                         | identity                       | No       | Batch content encoding (identity/gzip)                                                                                                          | No                                                        |
| filter (nested)                 | Filter                                              | -                              | No       | An optional filter                                                                                                                              | No                                                        |
| overflowAppenderRef (nested)    | String                                              | null                           | No       | An appender ref to which events are routed if the internal batch buffer is full (the implementation will first wait for `maxBlockOnOverflowMs`) | No                                                        |
| properties (nested)             | Property[]                                          | -                              | No       | Additional HTTP headers                                                                                                                         | No                                                        |
| httpClientSslConfigSupplier     | String                                              | null                           | No       | Class name for custom SSL config supplier                                                                                                       | No                                                        |
| batchSeparatorInsertionStrategy | separator insertion strategy (if_missing or always) | if_missing                     | No       | Strategy for inserting separators between events. `if_missing` means that separators are only inserted, if they are not already present.        | No                                                        |
| maxBatchBufferBytes             | int                                                 | `50 * maxBatchBytes + 250_000` | No       | Max total buffer size for batches (potentially compressed)                                                                                      | Yes                                                       |
| ignoreExceptions                | boolean                                             | true                           | No       | Ignore exceptions during logging                                                                                                                | Yes                                                       |
| batchCompletionListener         | String                                              | null                           | No       | Class name for custom batch completion listener                                                                                                 | Yes                                                       |

### Read this if you want to play with the `batchCompletionListener` option

The `AsyncHttpAppender` can expose internal data for monitoring purposes to a user supplied
`com.github.mlangc.more.log4j2.appenders.AsyncHttpAppender.BatchCompletionListener` implementation. Though the interface is 
simple, and hopefully self-explanatory, it is very easy to shoot yourself into the foot unless you read the following paragraph 
carefully:
1. Your `BatchCompletionListener` will be executed by the thread that drains the batch buffer. This thread must never be 
   blocked or monopolized for longer periods of time, or else your logs might not be drained in time.
2. Logs generated directly or indirectly from a `BatchCompletionListener` are problematic, since they lead to recursive
   invocations of the appender. If you most log, do so asynchronously, from another thread and ensure that the amount of logs that
   you generate in the listener is small compared to `maxBatchBytes` and
   `maxBatchLogEvents` to prevent feedback loops.

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




## Additional Notes

### Why not extend log4j2 directly?

My plan is to migrate the most useful parts of this library to mainline log4j2 at some point. However, log4j2 accepts new plugins
only if they have demonstrated long-term stability and have a broad user base. Please
see [this discussion](https://github.com/apache/logging-log4j2/discussions/3976) for details.

### License

This project is licensed under the Apache License 2.0.
