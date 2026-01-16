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
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.test.junit.LoggerContextSource;
import org.apache.logging.log4j.core.test.junit.Named;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@LoggerContextSource("MarkerBasedLogThrottlingUseCaseTest.xml")
class MarkerBasedLogThrottlingUseCaseTest {
    static final Marker THROTTLED_1 = MarkerManager.getMarker("throttled1");
    static final Marker THROTTLED_10 = MarkerManager.getMarker("throttled10");

    private final LoggerContext loggerContext;
    private final CountingAppender countingAppender;
    private final Logger log;

    MarkerBasedLogThrottlingUseCaseTest(LoggerContext loggerContext, @Named("CountingAppender") CountingAppender countingAppender) {
        this.loggerContext = loggerContext;
        this.countingAppender = countingAppender;
        this.log = loggerContext.getLogger(getClass());
    }

    @AfterEach
    void afterEach() {
        countingAppender.clear();
    }

    @Test
    void shouldParseAndUseRightConfig() {
        assertThat(loggerContext.getName()).contains(getClass().getSimpleName());
        assertThat(countingAppender).isNotNull();
    }

    @Test
    void shouldThrottleLogsBasedOnMarkers() {
        Runnable logTillStopped = () -> {
            for (int i = 0; i < 10_000; i++) {
                log.info(THROTTLED_1, "at most once per second");
                log.info(THROTTLED_10, "at most 10 times per second");
            }
        };

        List<CompletableFuture<Void>> futures = IntStream.range(0, 4)
                .mapToObj(ignore -> CompletableFuture.runAsync(logTillStopped))
                .collect(Collectors.toList());

        assertThat(futures).allSatisfy(f -> assertThat(f).succeedsWithin(1, TimeUnit.SECONDS));
        assertThat(countingAppender.currentCountWithoutMarker()).isZero();

        Map<String, Long> countsWithMarker = countingAppender.currentCountsWithMarkers();
        assertThat(countsWithMarker.getOrDefault(THROTTLED_1.getName(), 0L))
                .isNotZero()
                .isLessThan(countsWithMarker.getOrDefault(THROTTLED_10.getName(), 0L));
    }
}
