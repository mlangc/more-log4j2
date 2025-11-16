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
