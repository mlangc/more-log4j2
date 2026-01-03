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
