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
package com.github.mlangc.more.log4j2.filters;

import com.github.mlangc.more.log4j2.test.helpers.CountingAppender;
import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter.Result;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.test.junit.LoggerContextSource;
import org.apache.logging.log4j.core.test.junit.Named;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ThrottlingFilterTest {
    private static final Logger LOG = (Logger) LogManager.getLogger(ThrottlingFilterTest.class);

    @Test
    void shouldBehaveProperlyInSimpleScenarioWithFakeTicker() {
        long intervalNanos = TimeUnit.SECONDS.toNanos(1);
        long maxEventPerInterval = 3;
        MutableLong ticker = new MutableLong();
        ThrottlingFilter filter = new ThrottlingFilter(Result.ACCEPT, Result.DENY, Level.WARN, intervalNanos, maxEventPerInterval, ticker::getValue);

        assertThat(filter.filter(LOG, Level.INFO, null, "not throttled")).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.INFO, null, "not throttled")).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.INFO, null, "not throttled")).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.INFO, null, "throttled")).isEqualTo(Result.DENY);

        assertThat(filter.filter(LOG, Level.ERROR, null, "not throttled", (Object) null)).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.ERROR, null, "not throttled", null, null)).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.ERROR, null, "not throttled", null, null, null)).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.ERROR, null, "not throttled", null, null, null, null)).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.INFO, null, "throttled", null, null, null, null, null)).isEqualTo(Result.DENY);

        ticker.add(TimeUnit.SECONDS.toNanos(1));
        assertThat(filter.filter(LOG, Level.INFO, null, "not throttled", null, null, null, null, null, null)).isEqualTo(Result.ACCEPT);
    }

    @Test
    void shouldBehaveProperlyIfCalledWithAllOverloadsAndFakeTicker() {
        long intervalNanos = TimeUnit.SECONDS.toNanos(1);
        long maxEventPerInterval = 3;
        MutableLong ticker = new MutableLong();
        ThrottlingFilter filter = new ThrottlingFilter(Result.ACCEPT, Result.DENY, Level.WARN, intervalNanos, maxEventPerInterval, ticker::getValue);

        Map<Result, Integer> results = TestHelpers.filterWithAllOverloads(filter, LOG, Level.INFO, null, "test");
        assertThat(results).hasEntrySatisfying(Result.ACCEPT, v -> assertThat(v).isEqualTo(3));

        results = TestHelpers.filterWithAllOverloads(filter, LOG, Level.INFO, null, "test");
        assertThat(results).hasEntrySatisfying(Result.ACCEPT, v -> assertThat(v).isZero());

        ticker.add(intervalNanos);

        results = TestHelpers.filterWithAllOverloads(filter, LOG, Level.INFO, null, "test");
        assertThat(results).hasEntrySatisfying(Result.ACCEPT, v -> assertThat(v).isEqualTo(3));

        results = TestHelpers.filterWithAllOverloads(filter, LOG, Level.ERROR, null, "test");
        assertThat(results).hasEntrySatisfying(Result.DENY, v -> assertThat(v).isZero());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void shouldThrottleExcessiveLogsFromMultipleThreads(int parallelism) throws InterruptedException {
        try (LoggerContext context = TestHelpers.loggerContextFromTestResource(ThrottlingFilterTest.class.getSimpleName() + ".allowMany.xml")) {
            Configuration config = context.getConfiguration();
            ThrottlingFilter filter = (ThrottlingFilter) config.getFilter();

            ExtendedLogger log = context.getLogger(getClass());
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong startedAt = new AtomicLong(Long.MAX_VALUE);
            AtomicLong stoppedAt = new AtomicLong(Long.MIN_VALUE);

            Runnable logTillStopped = () -> {
                long startedNanos = System.nanoTime();
                startedAt.updateAndGet(t -> Math.min(t, startedNanos));
                while (!stop.get()) {
                    TestHelpers.logWithAllOverloads(log, null, "not yet stopped");
                }

                long stoppedNanos = System.nanoTime();
                stoppedAt.updateAndGet(t -> Math.max(t, stoppedNanos));
            };

            CompletableFuture<?>[] futures = IntStream.range(0, parallelism)
                    .mapToObj(ignore -> CompletableFuture.runAsync(logTillStopped))
                    .toArray(CompletableFuture<?>[]::new);

            long intervalNanos = filter.intervalNanos();
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(intervalNanos * 10) + 1);
            stop.set(true);
            assertThat(CompletableFuture.allOf(futures)).succeedsWithin(1, TimeUnit.SECONDS);

            long startedIntervals = Math.floorDiv(stoppedAt.get(), intervalNanos) - Math.floorDiv(startedAt.get(), intervalNanos) + 1;
            long maxLogs = startedIntervals * filter.maxEvents();

            CountingAppender countingAppender = context.getConfiguration().getAppender("CountingAppender");
            assertThat(countingAppender.currentCount())
                    .isLessThanOrEqualTo(maxLogs);
        }
    }


    @Test
    void shouldAllowOnlyMinimalAmountOfAdditionalLogsDueToRace() throws InterruptedException {
        try (LoggerContext context = TestHelpers.loggerContextFromTestResource(ThrottlingFilterTest.class.getSimpleName() + ".provokeRace.xml")) {
            ExtendedLogger log = context.getLogger(getClass());
            ThrottlingFilter throttlingFilter = (ThrottlingFilter) context.getConfiguration().getFilter();
            CountingAppender countingAppender = context.getConfiguration().getAppender("CountingAppender");

            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                for (int parallelism : new int[] { 16, 32, 64 }) {
                    AtomicBoolean stop = new AtomicBoolean();
                    AtomicLong started = new AtomicLong(Long.MAX_VALUE);
                    AtomicLong ended = new AtomicLong(Long.MIN_VALUE);
                    countingAppender.clear();

                    Runnable logTillStopped = () -> {
                        long t0 = System.nanoTime();
                        started.updateAndGet(s -> Math.min(s, t0));
                        while (!stop.get()) {
                            TestHelpers.logWithAllOverloads(log, "test");
                        }

                        long t1 = System.nanoTime();
                        ended.updateAndGet(e -> Math.max(e, t1));
                    };

                    List<CompletableFuture<?>> jobs = IntStream.range(0, parallelism)
                            .mapToObj(ignore -> CompletableFuture.runAsync(logTillStopped, executor))
                            .collect(Collectors.toList());

                    Thread.sleep(10);
                    stop.set(true);

                    assertThat(jobs).allSatisfy(
                            job -> assertThat(job).succeedsWithin(1, TimeUnit.SECONDS));

                    long intervalsStarted = ended.get() / throttlingFilter.intervalNanos() - started.get() / throttlingFilter.intervalNanos() + 1;
                    long maxEvents = intervalsStarted * throttlingFilter.maxEvents();
                    long tolerance = (maxEvents + 99) / 100;
                    assertThat(countingAppender.currentCount())
                                .as("parallelism=%s", parallelism)
                            .isLessThanOrEqualTo(maxEvents + tolerance);
                }
            } finally {
                executor.shutdown();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    @LoggerContextSource("ThrottlingFilterTest.atLogger.allowFew.xml")
    void shouldThrottleLogsFromSingleThreadWithFilterAtLogger(LoggerContext context, @Named("CountingAppender") CountingAppender countingAppender) throws InterruptedException {
        Configuration config = context.getConfiguration();
        ThrottlingFilter filter = (ThrottlingFilter) config.getRootLogger().getFilter();
        ExtendedLogger log = context.getLogger(getClass());
        long intervalMillis = TimeUnit.NANOSECONDS.toMillis(filter.intervalNanos());

        TestHelpers.logWithAllOverloads(log, null, "one");
        TestHelpers.logWithAllOverloads(log, null, "two");
        assertThat(countingAppender.currentCount()).isOne();

        Thread.sleep(intervalMillis + 1);
        TestHelpers.logWithAllOverloads(log, null, "three");
        assertThat(countingAppender.currentCount()).isEqualTo(2);
    }

    @Test
    @LoggerContextSource("ThrottlingFilterTest.topLevel.allowFew.xml")
    void shouldThrottleLogsFromSingleThread(LoggerContext context, @Named("CountingAppender") CountingAppender countingAppender) throws InterruptedException {
        Configuration config = context.getConfiguration();
        ThrottlingFilter filter = (ThrottlingFilter) config.getFilter();
        ExtendedLogger log = context.getLogger(getClass());
        long intervalMillis = TimeUnit.NANOSECONDS.toMillis(filter.intervalNanos());

        TestHelpers.logWithAllOverloads(log, null, "one");
        TestHelpers.logWithAllOverloads(log, null, "two");
        assertThat(countingAppender.currentCount()).isOne();

        Thread.sleep(intervalMillis + 1);
        TestHelpers.logWithAllOverloads(log, null, "three");
        assertThat(countingAppender.currentCount()).isEqualTo(2);
    }
}
