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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogCaptorHakky54ReadmeCompatTest {
    static class FooService1 {
        private static final Logger LOGGER = LogManager.getLogger(FooService1.class);

        public void sayHello() {
            LOGGER.info("Keyboard not responding. Press any key to continue...");
            LOGGER.warn("Congratulations, you are pregnant!");
        }
    }

    @Test
    void shouldWorkInCaptureLogsExample() {
        try (var logCaptor = LogCaptor.forClass(FooService1.class)) {
            FooService1 fooService = new FooService1();
            fooService.sayHello();

            // Get logs based on level
            assertThat(logCaptor.getInfoLogs()).containsExactly("Keyboard not responding. Press any key to continue...");
            assertThat(logCaptor.getWarnLogs()).containsExactly("Congratulations, you are pregnant!");

            // Get all logs
            assertThat(logCaptor.getLogs())
                    .hasSize(2)
                    .contains(
                            "Keyboard not responding. Press any key to continue...",
                            "Congratulations, you are pregnant!"
                    );

        }
    }

    @Nested
    class ShouldWorkInInitOnceAndReuseExample {
        private static LogCaptor logCaptor;
        private static final String EXPECTED_INFO_MESSAGE = "Keyboard not responding. Press any key to continue...";
        private static final String EXPECTED_WARN_MESSAGE = "Congratulations, you are pregnant!";

        @BeforeAll
        public static void setupLogCaptor() {
            logCaptor = LogCaptor.forClass(FooService1.class);
        }

        @AfterEach
        public void clearLogs() {
            logCaptor.clearLogs();
        }

        @AfterAll
        public static void tearDown() {
            logCaptor.close();
        }

        @Test
        public void logInfoAndWarnMessagesAndGetWithEnum() {
            FooService1 service = new FooService1();
            service.sayHello();

            assertThat(logCaptor.getInfoLogs()).containsExactly(EXPECTED_INFO_MESSAGE);
            assertThat(logCaptor.getWarnLogs()).containsExactly(EXPECTED_WARN_MESSAGE);

            assertThat(logCaptor.getLogs()).hasSize(2);
        }

        @Test
        public void logInfoAndWarnMessagesAndGetWithString() {
            FooService1 service = new FooService1();
            service.sayHello();

            assertThat(logCaptor.getInfoLogs()).containsExactly(EXPECTED_INFO_MESSAGE);
            assertThat(logCaptor.getWarnLogs()).containsExactly(EXPECTED_WARN_MESSAGE);

            assertThat(logCaptor.getLogs()).hasSize(2);
        }
    }

    @Nested
    class ShouldWorkInClassThatLogsIfSpecificLevelHasBeenSet {
        static class FooService2 {
            private static final Logger LOGGER = LogManager.getLogger(FooService2.class);

            public void sayHello() {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Keyboard not responding. Press any key to continue...");
                }
                LOGGER.info("Congratulations, you are pregnant!");
            }
        }

        @Test
        public void shouldLogAccordingLogLevel() {
            try (LogCaptor logCaptor = LogCaptor.forClass(FooService2.class)) {
                logCaptor.setLogLevelToInfo();

                FooService2 fooService = new FooService2();
                fooService.sayHello();

                assertThat(logCaptor.getInfoLogs()).containsExactly("Congratulations, you are pregnant!");
                assertThat(logCaptor.getDebugLogs()).isEmpty();

                logCaptor.clearLogs();
                logCaptor.setLogLevelToDebug();

                fooService.sayHello();

                assertThat(logCaptor.getInfoLogs()).containsExactly("Congratulations, you are pregnant!");
                assertThat(logCaptor.getDebugLogs())
                        .containsExactly("Keyboard not responding. Press any key to continue...");
            }
        }
    }

    @Nested
    class ShouldWorkWithClassLoggingException {
        static class FooService3 {
            private static final Logger LOGGER = LogManager.getLogger(FooService3.class);

            public void sayHello() {
                try {
                    tryToSpeak();
                } catch (IOException e) {
                    LOGGER.error("Caught unexpected exception", e);
                }
            }

            private void tryToSpeak() throws IOException {
                throw new IOException("KABOOM!");
            }
        }

        @Test
        void captureLoggingEventsContainingException() {
            try (LogCaptor logCaptor = LogCaptor.forClass(FooService3.class)) {
                FooService3 service = new FooService3();
                service.sayHello();

                List<LogEvent> logEvents = logCaptor.getLogEvents();

                assertThat(logEvents).hasSize(1);

                LogEvent logEvent = logEvents.get(0);
                assertThat(logEvent.getMessage().getFormattedMessage()).isEqualTo("Caught unexpected exception");
                assertThat(logEvent.getLevel()).isEqualTo(Level.ERROR);
                assertThat(logEvent.getThrown()).isNotNull();

                assertThat(logEvent.getThrown())
                        .hasMessage("KABOOM!")
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Nested
    class ShouldWorkWithMDC {
        static class FooService4 {
            private static final Logger LOGGER = LogManager.getLogger(FooService4.class);

            public void sayHello() {
                try {
                    MDC.put("my-mdc-key", "my-mdc-value");
                    LOGGER.info("Howdy");
                } finally {
                    MDC.clear();
                }

                LOGGER.info("Hello there!");
            }
        }

        @Test
        void captureLoggingEventsContainingMdc() {
            try (LogCaptor logCaptor = LogCaptor.forClass(FooService4.class)) {
                FooService4 service = new FooService4();
                service.sayHello();

                List<LogEvent> logEvents = logCaptor.getLogEvents();

                assertThat(logEvents).hasSize(2);

                assertThat(logEvents.get(0).getContextData().toMap())
                        .hasSize(1)
                        .extractingByKey("my-mdc-key")
                        .isEqualTo("my-mdc-value");

                assertThat(logEvents.get(1).getContextData().toMap()).isEmpty();
            }
        }
    }

    @Nested
    class ShouldDisableSpammyLogs {
        static class SomeService {
            private static final Logger LOG = LogManager.getLogger(SomeService.class);

            void generateLogSpam() {
                for (int i = 0; i < 100; i++) {
                    LOG.warn("Spam: {}", i);
                }
            }
        }

        @AutoClose
        private final static LogCaptor logCaptorForSomeOtherService = LogCaptor.forClass(SomeService.class);

        @BeforeAll
        static void disableLogs() {
            logCaptorForSomeOtherService.disableLogs();
        }

        @AfterAll
        static void resetLogLevel() {
            logCaptorForSomeOtherService.resetLogLevel();
        }

        @Test
        void logInfoAndWarnMessages() {
            try (LogCaptor logCaptor = LogCaptor.forClass(FooService1.class)) {

                SomeService someService = new SomeService();
                someService.generateLogSpam();

                FooService1 service = new FooService1();
                service.sayHello();

                assertThat(logCaptor.getLogs())
                        .hasSize(2)
                        .contains(
                                "Keyboard not responding. Press any key to continue...",
                                "Congratulations, you are pregnant!"
                        );

                assertThat(logCaptorForSomeOtherService.getLogs()).isEmpty();
            }
        }
    }
}
