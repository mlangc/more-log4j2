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

        TestHelpers.withAlternativeLoggerContext(configBuilder, ignore ->
                assertThatIllegalStateException().isThrownBy(() -> LogCaptor.forClass(getClass())).withMessageContaining("readme"));
    }

    @Test
    void shouldFailWithExceptionIfCapturingAppenderIsDefinedTwice() {
        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();

        configBuilder = configBuilder
                .setStatusLevel(Level.WARN)
                .add(configBuilder.newAppender("Captor1", "Captor"))
                .add(configBuilder.newAppender("Captor2", "Captor"))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("Captor1")));

        TestHelpers.withAlternativeLoggerContext(configBuilder, ignore ->
                assertThatIllegalStateException().isThrownBy(() -> LogCaptor.forClass(getClass())).withMessageContaining("readme"));
    }
}
