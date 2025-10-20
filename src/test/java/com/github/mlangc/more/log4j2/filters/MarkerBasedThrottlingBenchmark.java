package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@Fork(1)
@Warmup(iterations = 3, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
public class MarkerBasedThrottlingBenchmark {
    static final Marker THROTTLED = MarkerManager.getMarker("throttled");

    @State(Scope.Benchmark)
    static class BenchmarkState {
        final LoggerContext context;
        final Logger log;

        BenchmarkState(String location) {
            this.context = Configurator.initialize(MarkerBasedThrottlingBenchmark.class.getSimpleName(), location);
            this.log = context.getLogger(MarkerBasedThrottlingBenchmark.class);
        }

        @TearDown
        public void tearDown() {
            context.close();
        }
    }

    static void main() throws RunnerException {
        new Runner(new OptionsBuilder().resultFormat(ResultFormatType.JSON).build()).run();
    }

    public static class WithRoutingFilterState extends BenchmarkState {
        public WithRoutingFilterState() {
            super("MarkerBasedThrottlingBenchmark.withRoutingFilter.xml");
        }
    }

    public static class WithLoggerFilterState extends BenchmarkState {
        public WithLoggerFilterState() {
            super("MarkerBasedThrottlingBenchmark.withLoggerFilter.xml");
        }
    }

    @Benchmark
    public void withGlobalRoutingFilter(WithRoutingFilterState state) {
        logWithStateLogger(state);
    }

    @Benchmark
    public void withLoggerFilter(WithLoggerFilterState state) {
        logWithStateLogger(state);
    }

    private static void logWithStateLogger(BenchmarkState state) {
        state.log.info("test");
        state.log.info(THROTTLED, "test - throttled");
    }
}
