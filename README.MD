# more-log4j2
A collection of plugins for log4j2.

## Features
### Routing Filter
I'd like to explain the `RoutingFilter` by illustrating how it can solve two use cases:
#### Use Case I: Marker Based Log Throttling
After defining a global filter, like
```xml
<Configuration status="WARN">
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

## Why not extend log4j2 directly?
My plan is to migrate the most useful parts of this library to mainline log4j2 if possible. However, before bothering the log4j2
maintainers with my ideas, I'd like to experiment and collect some feedback.

## License
This project is licensed under the Apache License 2.0.