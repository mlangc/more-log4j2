package com.github.mlangc.more.log4j2.captor;

import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

public class LogCaptorBrokenConfigsTest {

    @Test
    void shouldFailWithExceptionIfCapturingAppenderIsMissing() {
        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();

        configBuilder = configBuilder
                .setStatusLevel(Level.WARN)
                .add(configBuilder.newAppender("Null", "Null"))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("Null")));

        TestHelpers.withAlternativeLoggerContext(configBuilder, ignore -> {
            assertThatIllegalStateException().isThrownBy(() -> LogCaptor.forClass(getClass())).withMessageContaining("README.MD");
        });
    }

    @Test
    void shouldFailWithExceptionIfCapturingAppenderIsDefinedTwice() {
        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();

        configBuilder = configBuilder
                .setStatusLevel(Level.WARN)
                .add(configBuilder.newAppender("Captor1", "Captor"))
                .add(configBuilder.newAppender("Captor2", "Captor"))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("Captor1")));

        TestHelpers.withAlternativeLoggerContext(configBuilder, ignore -> {
            assertThatIllegalStateException().isThrownBy(() -> LogCaptor.forClass(getClass())).withMessageContaining("README.MD");
        });
    }
}
