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
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.RunnerException;

import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Fork(1)
@Warmup(iterations = 3, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
public class DebugLogsThrottlingBenchmark {
    @State(Scope.Thread)
    public static class BenchmarkState {
        private static final int NUM_BITS = 100_000;

        final LoggerContext context;
        final Logger log;

        @Param({"0.1", "0.9"})
        double debugFraction;

        int i;

        final BitSet debugBits = new BitSet(NUM_BITS);

        BenchmarkState(String location) {
            this.context = Configurator.initialize(MarkerBasedThrottlingBenchmark.class.getSimpleName(), location);
            this.log = context.getLogger(DebugLogsThrottlingBenchmark.class);
        }

        @Setup
        public void setup() {
            var rng = new Random(0);

            for (int j = 0; j < NUM_BITS; j++) {
                debugBits.set(j, rng.nextDouble() < debugFraction);
            }
        }

        @TearDown
        public void tearDown() {
            context.close();
        }

        void nextLog() {
            if (debugBits.get(i)) {
                log.debug("debug");
            } else {
                log.info("info");
            }

            if (++i == NUM_BITS) i = 0;
        }
    }

    public static class DebugFirstBenchmarkState extends BenchmarkState {
        public DebugFirstBenchmarkState() {
            super("DebugLogsThrottlingBenchmark.debugFirst.xml");
        }
    }

    public static class InfoFirstBenchmarkState extends BenchmarkState {
        public InfoFirstBenchmarkState() {
            super("DebugLogsThrottlingBenchmark.infoFirst.xml");
        }
    }

    public static void main(String[] args) throws RunnerException {
        var state = new DebugFirstBenchmarkState();
        state.debugFraction = 0.5;
        state.setup();

        while (true) {
            state.nextLog();
        }
    }

    @Benchmark
    public void debugFirst(DebugFirstBenchmarkState state) {
        state.nextLog();
    }

    @Benchmark
    public void infoFirst(InfoFirstBenchmarkState state) {
        state.nextLog();
    }
}
