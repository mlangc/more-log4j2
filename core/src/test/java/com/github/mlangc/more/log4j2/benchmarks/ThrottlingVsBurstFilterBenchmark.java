/*-
 * #%L
 * more-log4j2
 * %%
 * Copyright (C) 2025 - 2026 Matthias Langer
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

import com.github.mlangc.more.log4j2.filters.ThrottlingFilter;
import com.github.mlangc.more.log4j2.test.helpers.CountingAppender;
import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.BurstFilter;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static java.lang.System.out;

@Fork(1)
@Warmup(iterations = 3, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Thread)
public class ThrottlingVsBurstFilterBenchmark {
    private static final int DEFAULT_RATE_DIVISOR = 20;
    private static final Logger LOG = (Logger) LogManager.getLogger(ThrottlingVsBurstFilterBenchmark.class);

    ThreadLocalRandom random;

    @Setup
    public void setup() {
        random = ThreadLocalRandom.current();
    }

    @State(Scope.Benchmark)
    public abstract static class AbstractState {
        @Param({"1000"})
        long rate;

        @Param({"0", "50", "100"})
        int burnTokens;
    }

    public static class DirectInvocationState extends AbstractState {
        ThrottlingFilter throttlingFilter;
        BurstFilter burstFilter;

        @Setup
        public void setup() {
            burstFilter = BurstFilter.newBuilder().setRate(rate).setMaxBurst(rate / DEFAULT_RATE_DIVISOR).build();

            long intervalNanos = TimeUnit.SECONDS.toNanos(1) / DEFAULT_RATE_DIVISOR;
            throttlingFilter = ThrottlingFilter.create(
                    Filter.Result.NEUTRAL,
                    Filter.Result.DENY,
                    Level.WARN,
                    intervalNanos,
                    TimeUnit.NANOSECONDS,
                    rate / DEFAULT_RATE_DIVISOR);
        }
    }

    public abstract static class LoggerContextAndLoggerState extends AbstractState {
        LoggerContext loggerContext;
        ExtendedLogger log;
        CountingAppender countingAppender;

        @Setup
        public void setup() {
            loggerContext = TestHelpers.loggerContextFromTestResource(configPath());
            log = loggerContext.getLogger(ThrottlingVsBurstFilterBenchmark.class);
            countingAppender = loggerContext.getConfiguration().getAppender("CountingAppender");
        }

        @TearDown
        public void tearDown() {
            out.println();
            out.printf("Counts with levels: %s%n", countingAppender.currentCountsWithLevels());
            out.println();
            loggerContext.close();
        }

        abstract String configPath();
    }


    public static class ThrottlingFilterAtTopLevelState extends LoggerContextAndLoggerState {
        @Override
        String configPath() {
            return ThrottlingVsBurstFilterBenchmark.class.getSimpleName() + ".throttlingFilter.rate" + rate + ".xml";
        }
    }

    public static class BurstFilterAtTopLevelState extends LoggerContextAndLoggerState {
        @Override
        String configPath() {
            return ThrottlingVsBurstFilterBenchmark.class.getSimpleName() + ".burstFilter.rate" + rate + ".xml";
        }
    }

    @Benchmark
    public Filter.Result burstFilterDirect(DirectInvocationState state) {
        Blackhole.consumeCPU(state.burnTokens);
        return state.burstFilter.filter(LOG, Level.INFO, null, "test");
    }

    @Benchmark
    public Filter.Result throttlingFilterDirect(DirectInvocationState state) {
        Blackhole.consumeCPU(state.burnTokens);
        return state.throttlingFilter.filter(LOG, Level.INFO, null, "test");
    }

    @Benchmark
    public void burstFilterTopLevel(BurstFilterAtTopLevelState state) {
        Blackhole.consumeCPU(state.burnTokens);
        state.log.info("test");
    }

    @Benchmark
    public void throttlingFilterTopLevel(ThrottlingFilterAtTopLevelState state) {
        Blackhole.consumeCPU(state.burnTokens);
        state.log.info("test");
    }
}
