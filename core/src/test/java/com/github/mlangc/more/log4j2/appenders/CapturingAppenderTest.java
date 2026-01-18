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

import com.github.mlangc.more.log4j2.captor.LogCaptor;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.test.junit.LoggerContextSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CapturingAppenderTest {
    @Test
    @LoggerContextSource("CapturingAppenderWithFilterTest.xml")
    void shouldRespectFilters(LoggerContext context) {
        try (var captor = LogCaptor.forClass(getClass())) {
            var log = context.getLogger(getClass());
            var marker = MarkerManager.getMarker("dont-capture");
            log.info(marker, "invisible");
            log.info("seeing is believing");

            assertThat(captor.getInfoLogs()).containsExactly("seeing is believing");
        }
    }
}
