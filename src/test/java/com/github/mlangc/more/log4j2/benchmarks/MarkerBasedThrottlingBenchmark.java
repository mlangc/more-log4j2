/*-
 * #%L
 * more-log4j2
 * %%
 * Copyright (C) 2025 Matthias Langer
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.github.mlangc.more.log4j2.benchmarks;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.openjdk.jmh.annotations.*;

import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Fork(1)
@Warmup(iterations = 3, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
public class MarkerBasedThrottlingBenchmark {
    static final Marker THROTTLED = MarkerManager.getMarker("throttled");

    @State(Scope.Thread)
    public static class BenchmarkState {
        private static final int NUM_BITS = 100_000;

        final LoggerContext context;
        final Logger log;

        @Param({"0.1", "0.5", "0.9"})
        double throttledFraction;

        int i;

        final BitSet throttledBits = new BitSet(NUM_BITS);

        BenchmarkState(String location) {
            this.context = Configurator.initialize(MarkerBasedThrottlingBenchmark.class.getSimpleName(), location);
            this.log = context.getLogger(MarkerBasedThrottlingBenchmark.class);
        }

        @Setup
        public void setup() {
            Random rng = new Random(0);

            for (int j = 0; j < NUM_BITS; j++) {
                throttledBits.set(j, rng.nextDouble() < throttledFraction);
            }
        }

        @TearDown
        public void tearDown() {
            context.close();
        }

        void nextLog() {
            if (throttledBits.get(i)) {
                log.info(THROTTLED, "throttled");
            } else {
                log.info("not throttled");
            }

            if (++i == NUM_BITS) i = 0;
        }
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

    public static class WithNoMarkerFirstRoutingFilterState extends BenchmarkState {
        public WithNoMarkerFirstRoutingFilterState() {
            super("MarkerBasedThrottlingBenchmark.withNoMarkerFirstRoutingFilter.xml");
        }
    }

    @Benchmark
    public void withGlobalRoutingFilter(WithRoutingFilterState state) {
        state.nextLog();
    }

    @Benchmark
    public void withMarkerFirstGlobalRoutingFilter(WithNoMarkerFirstRoutingFilterState state) {
        state.nextLog();
    }

    @Benchmark
    public void withLoggerFilter(WithLoggerFilterState state) {
        state.nextLog();
    }
}
