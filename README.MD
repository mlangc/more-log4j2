# more-log4j2
[![Maven Central](https://img.shields.io/maven-central/v/com.github.mlangc/more-log4j2.svg?label=Maven%20Central)](https://search.maven.org/artifact/com.github.mlangc/more-log4j2)

A collection of plugins for log4j2.

## Installation

This library is available [on Maven Central](https://central.sonatype.com/artifact/com.github.mlangc/more-log4j2?smo=true) under
the coordinates `com.github.mlangc:more-log4j2`. It requires only log4j2 and at least Java 8.

## Features

### Routing Filter

I'd like to explain `RoutingFilter` by illustrating how it can solve two use cases, that go beyond what you can do with
standard log4j2, unless you fall back to using [ScriptFilter](https://logging.apache.org/log4j/2.x/manual/filters.html#Script),
or write a plugin, as I did.

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
for (var route : routes) {
    if (route.accepts(event)) {
        route.apply(event);
        return;
    }
}

defaultRoute.apply(event);
```

Whenever you are free to reorder `FilterRoute` elements because their matching sets don't overlap, I'd suggest to use the
order that makes your config the most readable. Putting the most commonly taken routes first might save you a few CPU cycles,
however, apart from extreme cases, where millions of logs are filtered down to a handful of lines every second, this won't 
make any difference.

### AcceptAllFilter & NeutralFilter

These two filters don't have any options, and always return either `ACCEPT` or `NEUTRAL`. They complement
[DenyAllFilter](https://logging.apache.org/log4j/2.x/manual/filters.html#deny-filter), which exists in mainline log4j2, and are
especially useful in connection with `RoutingFilter`.

## Throttling Filter

`ThrottlingFilter` is an alternative to [BurstFilter](https://logging.apache.org/log4j/2.x/manual/filters.html#BurstFilter),
that provides roughly the same functionality, but with less overhead and without object allocations. Let's look at a few
examples:

#### Throttling WARN and below at most one log per second:
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

### ThrottlingFilter Configuration

| Attribute | Type                           | Default | Description                                                     |
|-----------|--------------------------------|---------|-----------------------------------------------------------------|
| interval  | long                           |         | The throttling interval.                                        |
| timeUnit  | java.util.concurrent.TimeUnit  |         | The time unit of the throttling interval                        |
| maxEvents | long                           |         | Maximal number of log events per interval                       |
| level     | org.apache.logging.log4j.Level | WARN    | Only log events up until and including this level are throttled |

Conceptually, `ThrottlingFilter` divides the timeline into fixed intervals according to the `interval` configuration above, and
allows at most `maxEvents` logs in each interval. Note that this is subtly different from the `BurstFilter`, which maintains a
sliding window of length `maxBurst / rate` seconds, and allows at most `maxBurst` logs in this window. For most practical 
purposes, this difference should be negligible.

## Additional Notes

### Is that all?

I have some additional ideas, but would like to start small, and collect some feedback first.

### Why not extend log4j2 directly?

My plan is to migrate the most useful parts of this library to mainline log4j2 at some point. However, log4j2 accepts new plugins
only if they have demonstrated long-term stability and have a broad user base. Please
see [this discussion](https://github.com/apache/logging-log4j2/discussions/3976) for details.

### License

This project is licensed under the Apache License 2.0.