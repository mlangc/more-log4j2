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
want to be on the save side, and reliably avoid log spam. Then `RoutingFilter` can help you as follows:

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

In Java, the behaviour of the filter can be summarized as follows:

```java
for(var route :routes){
        if(route.

accepts(event)){
        route.

apply(event);
        return;
                }
                }

                defaultRoute.

apply(event);
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
overhead. The implementation has been optimized for resiliency, and will never block application threads because a backend is
unreachable or unresponsive.

##### Tradeoffs

The implementation makes some tradeoffs, that I clearly want to spell out here. The basic assumption is that logging is an
important, but *secondary* concern of your application. Therefore, the appender tries it's best to keep a low profile, and
performs most of its work in a separate, single threaded thread pool asynchronously. If an HTTP backend is not keeping up, or
temporarily unavailabe, unsent log batches will accumulate in an internal buffer (see `maxBatchBufferBytes`). If this buffer runs
full, batches will be dropped. By default, this will result in a warning being logged to the
[StatusLogger](https://logging.apache.org/log4j/2.x/manual/status-logger.html), but you can override this behavior by providing a
`batchCompletionListener`.

Another tradeoff concerns the way that individual log events are combined into batches. While more advanced schemes can be
imagined, I decided to stick with the simple approach of combining byte arrays of rendered log events into a batches by using a
prefix, a separator and a suffix. While this constrains the usage somewhat, it's still relatively flexible, and allows for a very
efficient and predicatable implementation.

#### Publishing Logs to Dynatrace

To publish logs to the 
[Dynatrace Log Monitoring API v2](https://docs.dynatrace.com/docs/discover-dynatrace/references/dynatrace-api/environment-api/log-monitoring-v2/post-ingest-logs)
you can use the following recipe: 
 
* At first make sure to add a dependency to the 
[JSON template layout](https://logging.apache.org/log4j/2.x/manual/json-template-layout.html)
that is part of mainline log4j2, but is shipped in a separate JAR.
* Then create a layout file to format your log messages that looks like
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
* Now you can use the following configuration
  ```xml
  <Configuration status="WARN">
      <Properties>
          <Property name="pattern" value="%d{HH:mm:ss.SSS} %-5level %logger{1} - %msg%n"/>
      </Properties>
      <Appenders>
          <AsyncHttp name="Dynatrace" url="${env:DYNATRACE_API_V2_LOGS_INGEST}" maxBatchBytes="10000000" maxBatchLogEvents="50000" httpSuccessCodes="200,204"
                     httpRetryCodes="429,503" contentEncoding="gzip" >
              <Property name="Authorization" value="Api-Token ${env:DYNATRACE_API_TOKEN}"/>
              <Property name="Content-Type" value="application/jsonl"/>
              <JsonTemplateLayout eventTemplateUri="classpath:DynatraceLogMessageJsonLayout.json">
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
              <AppenderRef ref="Console"/>
              <AppenderRef ref="Dynatrace"/>
          </Root>
      </Loggers>
  </Configuration>
```



#### Publishing Logs to Datadog

#### Publishing Logs to Grafana

#### Configuration

| Parameter                       | Type                                                | Default Value      | Required | Description                                         | Expert Only |
|---------------------------------|-----------------------------------------------------|--------------------|----------|-----------------------------------------------------|-------------|
| name                            | String                                              | -                  | Yes      | Appender name                                       | No          |
| url                             | URI                                                 | -                  | Yes      | Target HTTP endpoint                                | No          |
| layout (nested element)         | Layout                                              | -                  | Yes      | A layout to format log messages                     | No          |
| lingerMs                        | int                                                 | 5000               | No       | Max time to wait before sending a batch             | No          |
| maxBatchBytes                   | int                                                 | 250_000            | No       | Max size of a batch                                 | No          |
| maxBatchLogEvents               | int                                                 | 1000               | No       | Max number of log events per batch                  | No          |
| connectTimeoutMs                | int                                                 | 10_000             | No       | HTTP connection timeout                             | No          |
| readTimeoutMs                   | int                                                 | 10_000             | No       | HTTP read timeout                                   | No          |
| maxConcurrentRequests           | int                                                 | 5                  | No       | Max concurrent HTTP requests                        | No          |
| method                          | HTTP method                                         | POST               | No       | HTTP method (POST/PUT)                              | No          |
| batchPrefix                     | String                                              | ""                 | No       | Prefix for each batch                               | No          |
| batchSeparator                  | String                                              | "\n"               | No       | Separator between log events in a batch             | No          |
| batchSuffix                     | String                                              | ""                 | No       | Suffix for each batch                               | No          |
| httpSuccessCodes                | String                                              | 200, 202, 204      | No       | HTTP status codes considered successful             | No          |
| httpRetryCodes                  | String                                              | 500, 502, 503, 504 | No       | HTTP status codes that trigger a retry              | No          |
| retryOnIoError                  | boolean                                             | true               | No       | Retry on I/O errors                                 | No          |
| retries                         | int                                                 | 5                  | No       | Number of (exponentially backed off) retry attempts | No          |
| contentEncoding                 | encoding (identity or gzip)                         | identity           | No       | Batch content encoding (identity/gzip)              | No          |
| filter (nested)                 | Filter                                              | -                  | No       | An optional filter                                  | No          |
| properties (nested)             | Property[]                                          | -                  | No       | Additional HTTP headers                             | No          |
| ignoreExceptions                | boolean                                             | true               | No       | Ignore exceptions during logging                    | Yes         |
| maxBatchBufferBytes             | int                                                 | TODO               | No       | Max total buffer size for batches                   | Yes         |
| batchSeparatorInsertionStrategy | separator insertion strategy (if_missing or always) | if_missing         | No       | Strategy for inserting separators between events    | Yes         |
| httpClientSslConfigSupplier     | String                                              | null               | No       | Class name for custom SSL config supplier           | Yes         |
| batchCompletionListener         | String                                              | null               | No       | Class name for custom batch completion listener     | Yes         |

## Additional Notes

### Is that all?

I have some additional ideas, but would like to start small, and collect some feedback first.

### Why not extend log4j2 directly?

My plan is to migrate the most useful parts of this library to mainline log4j2 at some point. However, log4j2 accepts new plugins
only if they have demonstrated long-term stability and have a broad user base. Please
see [this discussion](https://github.com/apache/logging-log4j2/discussions/3976) for details.

### License

This project is licensed under the Apache License 2.0.