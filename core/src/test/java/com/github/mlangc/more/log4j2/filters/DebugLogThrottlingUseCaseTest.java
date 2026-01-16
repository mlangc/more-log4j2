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
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
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

@LoggerContextSource("DebugLogThrottlingUseCaseTest.xml")
class DebugLogThrottlingUseCaseTest {
    private final LoggerContext loggerContext;
    private final CountingAppender countingAppender;
    private final Logger log;

    DebugLogThrottlingUseCaseTest(LoggerContext loggerContext, @Named("CountingAppender") CountingAppender countingAppender) {
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
        Runnable generateLogSpam = () -> {
            for (int i = 0; i < 1000; i++) {
                log.debug("debug me tender");
                log.info("inform me early");
            }
        };

        List<CompletableFuture<Void>> futures = IntStream.range(0, 4)
                .mapToObj(ignore -> CompletableFuture.runAsync(generateLogSpam))
                .collect(Collectors.toList());

        assertThat(futures).allSatisfy(f -> assertThat(f).succeedsWithin(1, TimeUnit.SECONDS));

        Map<Level, Long> countsWithLevel = countingAppender.currentCountsWithLevels();
        assertThat(countsWithLevel.get(Level.DEBUG))
                .as("countsWithLevel=%s", countsWithLevel)
                .isLessThan(countsWithLevel.getOrDefault(Level.INFO, 0L));

        assertThat(countingAppender.currentCount())
                .isEqualTo(countsWithLevel.get(Level.INFO) + countsWithLevel.get(Level.DEBUG))
                .isEqualTo(5 + 10);
    }
}
