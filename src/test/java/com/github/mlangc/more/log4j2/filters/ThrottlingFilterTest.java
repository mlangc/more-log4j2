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
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

        assertThat(filter.filter(LOG, Level.INFO, null, "test")).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.INFO, null, "test")).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.INFO, null, "test")).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.INFO, null, "test")).isEqualTo(Result.DENY);

        assertThat(filter.filter(LOG, Level.WARN, null, "test", (Object) null)).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.WARN, null, "test", null, null)).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.WARN, null, "test", null, null, null)).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.WARN, null, "test", null, null, null, null)).isEqualTo(Result.ACCEPT);
        assertThat(filter.filter(LOG, Level.INFO, null, "test", null, null, null, null, null)).isEqualTo(Result.DENY);

        ticker.add(TimeUnit.SECONDS.toNanos(1));
        assertThat(filter.filter(LOG, Level.INFO, null, "test", null, null, null, null, null, null)).isEqualTo(Result.ACCEPT);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    void shouldResultInExpectedNumberOfLogsWithSampleConfig(int parallelism) throws InterruptedException {
        try (LoggerContext context = TestHelpers.loggerContextFromTestResource(ThrottlingFilterTest.class.getSimpleName() + ".xml")) {
            ExtendedLogger log = context.getLogger(getClass());
            AtomicBoolean stop = new AtomicBoolean();

            Runnable logTillStopped = () -> {
                while (!stop.get()) {
                    TestHelpers.logWithAllOverloads(log, null, "not yet stopped");
                }
            };

            long t0 = System.nanoTime();
            CompletableFuture<?>[] futures = IntStream.range(0, parallelism)
                    .mapToObj(ignore -> CompletableFuture.runAsync(logTillStopped))
                    .toArray(CompletableFuture<?>[]::new);

            Thread.sleep(10);
            stop.set(true);
            assertThat(CompletableFuture.allOf(futures)).succeedsWithin(1, TimeUnit.SECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) ;

            CountingAppender countingAppender = context.getConfiguration().getAppender("CountingAppender");

            assertThat(countingAppender.currentCount())
                    .isLessThan(elapsedMillis + 1)
                    .isGreaterThanOrEqualTo(elapsedMillis);
        }
    }

    @Test
    @LoggerContextSource("ThrottlingFilterTest.xml")
    void shouldLoadFilterFromSampleConfig(Configuration configuration) {
        assertThat(configuration.getFilter()).isInstanceOf(ThrottlingFilter.class);
    }
}
