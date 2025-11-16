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
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.test.junit.LoggerContextSource;
import org.apache.logging.log4j.core.test.junit.Named;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ThrottlingFilterDocumentedExamplesTest {
    @Test
    @LoggerContextSource("ThrottlingFilterDocumentedExamplesTest.oncePerSecond.xml")
    void shouldThrottleToOncePerSecond(LoggerContext context, @Named("CountingAppender") CountingAppender countingAppender) {
        Logger logBurstFilter = context.getLogger(getClass().getCanonicalName() + ".burstFilter");
        Logger logThrottlingFilter = context.getLogger(getClass().getCanonicalName() + ".throttlingFilter");

        TestHelpers.logWithAllOverloads(logBurstFilter, "test");
        assertThat(countingAppender.currentCount()).isEqualTo(1);
        countingAppender.clear();

        TestHelpers.logWithAllOverloads(logThrottlingFilter, "test");
        assertThat(countingAppender.currentCount()).isBetween(1L, 2L); // <-- need range for the unlikely event where an interval boundary splits log events
    }

    @Test
    @LoggerContextSource("ThrottlingFilterDocumentedExamplesTest.debugOncePerSecondBurst10.xml")
    void shouldThrottleToOncePerSecondButAllowBurstsOf10(LoggerContext context, @Named("CountingAppender") CountingAppender countingAppender) {
        Logger logBurstFilter = context.getLogger(getClass().getCanonicalName() + ".burstFilter");
        Logger logThrottlingFilter = context.getLogger(getClass().getCanonicalName() + ".throttlingFilter");

        for (int i = 0; i < 20; i++) {
            logBurstFilter.debug("test");
        }

        assertThat(countingAppender.currentCount()).isEqualTo(10);
        countingAppender.clear();

        for (int i = 0; i < 100; i++) {
            logThrottlingFilter.debug("test");
        }

        assertThat(countingAppender.currentCount()).isBetween(10L, 20L); // <-- need range for the unlikely case where an interval boundary splits log events
        countingAppender.clear();

        for (int i = 0; i < 100; i++) {
            logBurstFilter.info("test");
        }

        assertThat(countingAppender.currentCount()).isEqualTo(100);
        countingAppender.clear();

        for (int i = 0; i < 100; i++) {
            logThrottlingFilter.info("test");
        }

        assertThat(countingAppender.currentCount()).isEqualTo(100);
    }
}
