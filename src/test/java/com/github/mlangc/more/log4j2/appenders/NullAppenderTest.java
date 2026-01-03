package com.github.mlangc.more.log4j2.appenders;

import com.github.mlangc.more.log4j2.test.helpers.CountingAppender;
import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LifeCycle;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.test.junit.LoggerContextSource;
import org.apache.logging.log4j.core.test.junit.Named;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NullAppenderTest {
    @Test
    @LoggerContextSource("NullAppenderTest.basicConfig.xml")
    void shouldLoadBasicConfig(LoggerContext context, @Named("Null") NullAppender nullAppender) {
        assertThat(nullAppender).isNotNull();
        assertThat(nullAppender.getState()).isEqualTo(LifeCycle.State.STARTED);
    }

    @Test
    void shouldLoadAndWorkAsExpectedWithArbiterBasedConfig() {
        try (var context = TestHelpers.loggerContextFromTestResource("NullAppenderTest.arbiterBasedConfig.xml")) {
            var log = context.getLogger(getClass());
            var count = TestHelpers.findAppender(context, CountingAppender.class);
            assertThat((Appender) context.getConfiguration().getAppender("Main")).isInstanceOf(CountingAppender.class);

            log.info("test");

            assertThat(count.currentCount()).isOne();
        }

        System.setProperty("log4j.select.appender", "null");

        try (var context = TestHelpers.loggerContextFromTestResource("NullAppenderTest.arbiterBasedConfig.xml")) {
            var log = context.getLogger(getClass());
            assertThat((Appender) context.getConfiguration().getAppender("Main")).isInstanceOf(NullAppender.class);
            log.info("test");
        }
    }
}