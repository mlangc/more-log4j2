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
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LogCaptorTest {
    private static final Logger LOG = LogManager.getLogger(LogCaptorTest.class);

    @AutoClose
    final LogCaptor logCaptor = LogCaptor.forClass(getClass());

    @Test
    void shouldCaptureLogsFromDifferentThread() {
        assertThat(CompletableFuture.runAsync(() -> LOG.info("test"))).succeedsWithin(1, TimeUnit.SECONDS);
        assertThat(logCaptor.getInfoLogs()).containsExactly("test");
    }

    @Test
    void shouldSetAndResetLogLevelsCorrectly() {
        logCaptor.setLogLevel(Level.WARN);
        LOG.info("should be suppressed");
        assertThat(logCaptor.getLogs()).isEmpty();

        logCaptor.resetLogLevel();
        LOG.info("should not be suppressed");
        assertThat(logCaptor.getInfoLogs()).hasSize(1);
    }

   @Test
   void shouldCaptureDebugLogs() {
       logCaptor.setLogLevelToDebug();
       LOG.debug("debug message");
       assertThat(logCaptor.getDebugLogs()).containsExactly("debug message");
   }

   @Test
   void shouldCaptureWarnLogs() {
       LOG.warn("warning message");
       assertThat(logCaptor.getWarnLogs()).containsExactly("warning message");
   }

   @Test
   void shouldCaptureMultipleLogsAtSameLevel() {
       LOG.info("first");
       LOG.info("second");
       LOG.info("third");
       assertThat(logCaptor.getInfoLogs()).containsExactly("first", "second", "third");
   }

   @Test
   void shouldCaptureAllLogsRegardlessOfLevel() {
       logCaptor.setLogLevelToTrace();
       LOG.trace("trace");
       LOG.debug("debug");
       LOG.info("info");
       LOG.warn("warn");
       assertThat(logCaptor.getLogs()).containsExactly("trace", "debug", "info", "warn");
   }

   @Test
   void shouldClearLogsSuccessfully() {
       LOG.info("before clear");
       logCaptor.clearLogs();
       LOG.info("after clear");
       assertThat(logCaptor.getInfoLogs()).containsExactly("after clear");
   }

   @Test
   void shouldDisableLogsCompletely() {
       logCaptor.disableLogs();
       LOG.error("should not appear");
       assertThat(logCaptor.getLogs()).isEmpty();
   }

   @Test
   void shouldGetLogEventsWithMetadata() {
       LOG.info("test message");
       assertThat(logCaptor.getLogEvents())
           .hasSize(1)
           .allSatisfy(event -> {
               assertThat(event.getMessage().getFormattedMessage()).isEqualTo("test message");
               assertThat(event.getLevel()).isEqualTo(Level.INFO);
               assertThat(event.getLoggerName()).isEqualTo(LOG.getName());
           });
   }

   @Test
   void shouldFilterLogsByLevel() {
       logCaptor.setLogLevelToDebug();
       LOG.trace("trace");
       LOG.debug("debug");
       LOG.info("info");
       LOG.warn("warn");
       assertThat(logCaptor.getLogs(Level.DEBUG)).containsExactly("debug");
       assertThat(logCaptor.getLogs(Level.INFO)).containsExactly("info");
       assertThat(logCaptor.getLogs(Level.WARN)).containsExactly("warn");
   }

   @Test
   void shouldNotCaptureLogsFromOtherLoggers() {
       Logger otherLogger = LogManager.getLogger("other.logger");
       otherLogger.info("from other logger");
       assertThat(logCaptor.getLogs()).isEmpty();
   }
}
