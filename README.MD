# more-log4j2

A collection of plugins for log4j2.

## Features

### Routing Filter

I'd like to explain `RoutingFilter` by illustrating how it can solve two use cases:

#### Use Case I: Marker Based Log Throttling

After defining a global filter, like

```xml

<Configuration status="warn">
    <Filters>
        <RoutingFilter>
            <FilterRoute>
                <FilterRouteIf>
                    <MarkerFilter marker="throttled10" onMatch="ACCEPT"/>
                </FilterRouteIf>
                <FilterRouteThen>
                    <BurstFilter rate="10" maxBurst="20"/>
                </FilterRouteThen>
            </FilterRoute>

            <FilterRoute>
                <FilterRouteIf>
                    <MarkerFilter marker="throttled1" onMatch="ACCEPT"/>
                </FilterRouteIf>
                <FilterRouteThen>
                    <BurstFilter rate="1" maxBurst="2"/>
                </FilterRouteThen>
            </FilterRoute>

            <DefaultFilterRoute>
                <NeutralFilter/>
            </DefaultFilterRoute>
        </RoutingFilter>
    </Filters>
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
    LOG.info(THROTTLED_10, "Throttled to 10 times per sec");
    LOG.info("Not throttled at all");
}
```

#### Use Case II: Throttling DEBUG Logs

Let's imagine that you want to enable `DEBUG` or `TRACE` logs, at least for parts of the executed code. At the same time, you want
to be on the save side, and avoid log spam reliably. Then `RoutingFilter` can help you as follows:

```xml

<Configuration status="warn">
    <Filters>
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
                <BurstFilter rate="1" maxBurst="5"/>
            </DefaultFilterRoute>
        </RoutingFilter>
    </Filters>
    <!-- ... -->
    <!-- ... -->
    <!-- ... -->
</Configuration>
```

#### Reference

`RoutingFilter` has no attributes, and is configured by its nested `FilterRoute` and `DefaultFilterRoute` elements:
Each filter route must contain two child elements, `FilterRouteIf` and `FilterRouteThen` and both of them must contain filters
themselves. If the nested filter in `FilterRouteIf` returns `ACCEPT` (note that `NEUTRAL` is not enough), the filter branch in
`FilterRouteThen` is taken and all remaining `FilterRoute` elements, as well as the mandatory `DefaultFilterRoute`
are skipped. If no `FilterRoute` matches, the filters in `DefaultFilterRoute` are applied.

### AcceptAllFilter & NeutralFilter

These two filters don't have any options, and always return either `ACCEPT` or `NEUTRAL`. They complement 
[DenyAllFilter](https://logging.apache.org/log4j/2.x/manual/filters.html#deny-filter), which exists in mainline log4j2,
and are especially useful in connection with `RoutingFilter`.

### Is that all?
I have some additional ideas, but would like to start small, and collect some feedback first.

## FAQ
### Why not extend log4j2 directly?

My plan is to migrate the most useful parts of this library to mainline log4j2 if possible. However, before bothering the log4j2
maintainers with my ideas, I'd like to experiment and collect some feedback.

## License

This project is licensed under the Apache License 2.0.