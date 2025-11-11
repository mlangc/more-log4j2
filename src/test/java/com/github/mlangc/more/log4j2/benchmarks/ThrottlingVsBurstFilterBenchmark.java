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

import com.github.mlangc.more.log4j2.filters.ThrottlingFilter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.BurstFilter;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@Fork(1)
@Warmup(iterations = 3, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class ThrottlingVsBurstFilterBenchmark {
    private static final long DEFAULT_RATE_MULTIPLE = 100;
    private static final Logger LOG = (Logger) LogManager.getLogger(ThrottlingVsBurstFilterBenchmark.class);

    @Param({"10"})
    long rate;

    ThrottlingFilter throttlingFilter;
    BurstFilter burstFilter;

    @Setup
    public void setup() {
        burstFilter = BurstFilter.newBuilder().setRate(rate).build();

        long intervalNanos = TimeUnit.SECONDS.toNanos(DEFAULT_RATE_MULTIPLE);
        throttlingFilter = ThrottlingFilter.create(
                Filter.Result.NEUTRAL,
                Filter.Result.DENY,
                Level.WARN,
                intervalNanos,
                TimeUnit.NANOSECONDS,
                 rate * 100);
    }

    @Benchmark
    public Filter.Result burstFilter() {
        return burstFilter.filter(LOG, Level.INFO, null, "test");
    }

    @Benchmark
    public Filter.Result throttlingFilter() {
        return throttlingFilter.filter(LOG, Level.INFO, null, "test");
    }
}
