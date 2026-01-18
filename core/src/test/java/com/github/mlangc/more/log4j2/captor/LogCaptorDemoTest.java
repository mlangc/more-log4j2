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


import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

public class LogCaptorDemoTest {
    private static final Logger LOG = LoggerFactory.getLogger(LogCaptorDemoTest.class);

    static class Service1 {
        private static final Logger LOG = LoggerFactory.getLogger(Service1.class);
    }

    static class Service2 {
        private static final Logger LOG = LoggerFactory.getLogger(Service2.class);
    }

    @AutoClose
    private final LogCaptor service1Captor = LogCaptor.forClass(Service1.class);

    @AutoClose
    private final LogCaptor service2Captor = LogCaptor.forClass(Service2.class);

    @Test
    void shouldNotCaptureAnythingIfNothingHappens() {
        assertThat(service1Captor.getLogs()).isEmpty();
        assertThat(service2Captor.getLogs()).isEmpty();
    }

    @Test
    void shouldCaptureInfoLogs() {
        Service1.LOG.info("Hello service 1");
        Service2.LOG.info("Hello service 2");

        assertThat(service1Captor.getInfoLogs()).containsExactly("Hello service 1");
        assertThat(service2Captor.getInfoLogs()).containsExactly("Hello service 2");
    }

    @Test
    void shouldCaptureInfoAndWarnAndErrorLogs() {
        Service1.LOG.info("Info");
        Service1.LOG.warn("Warn");
        Service1.LOG.error("Error");

        assertThat(service1Captor.getInfoLogs()).containsExactly("Info");
        assertThat(service1Captor.getWarnLogs()).containsExactly("Warn");
        assertThat(service1Captor.getErrorLogs()).containsExactly("Error");
        assertThat(service1Captor.getLogs()).containsExactly("Info", "Warn", "Error");
    }

    @Test
    void shouldCaptureExceptions() {
        Service1.LOG.warn("Ups", new RuntimeException("darn"));

        assertThat(service1Captor.getLogEvents()).hasSize(1).first()
                .satisfies(evt -> assertThat(evt.getThrown()).hasMessage("darn"));
    }

    @Test
    void shouldCaptureMdc() {
        try (var ignore = MDC.putCloseable("test", "me")) {
            Service1.LOG.info("Test");
        }

        assertThat(service1Captor.getLogEvents()).hasSize(1).first()
                .satisfies(evt -> assertThat(evt.getContextData().toMap()).containsExactly(Map.entry("test", "me")));
    }

    @Test
    void shouldNotCaptureDebugLogsUnlessEnabled() {
        Service1.LOG.debug("Not captured");
        assertThat(service1Captor.getDebugLogs()).isEqualTo(service1Captor.getLogs()).isEmpty();

        service1Captor.setLogLevelToDebug();
        Service1.LOG.debug("Captured");

        assertThat(service1Captor.getDebugLogs())
                .isEqualTo(service1Captor.getLogs())
                .containsExactly("Captured");
    }

    @Test
    void shouldClearLogs() {
        Service1.LOG.info("Test 1");
        assertThat(service1Captor.getInfoLogs()).hasSize(1);

        service1Captor.clearLogs();
        assertThat(service1Captor.getInfoLogs()).isEmpty();

        Service1.LOG.info("Test 2");
        assertThat(service1Captor.getInfoLogs()).hasSize(1);

        service1Captor.clearLogs();
        assertThat(service1Captor.getInfoLogs()).isEmpty();
    }

    @Test
    void shouldNotCaptureLogsWhileDisabled() {
        service1Captor.disableLogs();
        Service1.LOG.info("Nada");
        assertThat(service1Captor.getLogs()).isEmpty();

        service1Captor.resetLogLevel();
        Service1.LOG.info("Tada");
        assertThat(service1Captor.getLogs()).containsExactly("Tada");
    }

    // Use this with care, since captured logs are kept in memory till the captor is closed
    @AutoClose
    private final LogCaptor rootCaptor = LogCaptor.forRoot();

    @Test
    void rootCaptorShouldCaptureEverything() {
        LOG.info("Hello 0");
        Service1.LOG.info("Hello 1");
        Service2.LOG.info("Hello 2");

        assertThat(rootCaptor.getInfoLogs()).containsExactly("Hello 0", "Hello 1", "Hello 2");
        assertThat(service1Captor.getInfoLogs()).containsExactly("Hello 1");
        assertThat(service2Captor.getInfoLogs()).containsExactly("Hello 2");
    }

    @Test
    void baseCaptorShouldCaptureLogsFromBothServices() {
        try (var baseCaptor = LogCaptor.forName("com.github.mlangc.more.log4j2")) {
            Service1.LOG.info("Howdy");
            Service1.LOG.info("Hello");

            assertThat(baseCaptor.getInfoLogs()).containsExactly("Howdy", "Hello");
        }
    }

    @Test
    void shouldCaptureLogsFromOtherThread() {
        CompletableFuture.runAsync(() -> Service1.LOG.info("async")).join();
        assertThat(service1Captor.getInfoLogs()).containsExactly("async");
    }
}
