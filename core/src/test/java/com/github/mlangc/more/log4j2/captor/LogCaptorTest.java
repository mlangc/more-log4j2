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
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class LogCaptorTest {
    private static final Logger LOG = LogManager.getLogger(LogCaptorTest.class);

    static class Service1 {
        private static final Logger LOG = LogManager.getLogger(Service1.class);
    }

    static class Service2 {
        private static final Logger LOG = LogManager.getLogger(Service2.class);
    }

    @AutoClose
    final LogCaptor logCaptor0 = LogCaptor.forClass(getClass());

    @AutoClose
    final LogCaptor logCaptor1 = LogCaptor.forClass(Service1.class);

    @AutoClose
    final LogCaptor logCaptor2 = LogCaptor.forClass(Service2.class);

    @AfterEach
    void afterEach() {
        logCaptor0.resetLogLevel();
        logCaptor1.resetLogLevel();
        logCaptor2.resetLogLevel();
    }

    @Test
    void shouldCaptureLogsFromDifferentThread() {
        assertThat(CompletableFuture.runAsync(() -> LOG.info("test"))).succeedsWithin(1, TimeUnit.SECONDS);
        assertThat(logCaptor0.getInfoLogs()).containsExactly("test");
    }

    @Test
    void shouldSetAndResetLogLevelsCorrectly() {
        logCaptor0.setLogLevel(Level.WARN);
        LOG.info("should be suppressed");
        assertThat(logCaptor0.getLogs()).isEmpty();

        logCaptor0.resetLogLevel();
        LOG.info("should not be suppressed");
        assertThat(logCaptor0.getInfoLogs()).hasSize(1);
    }

    @Test
    void shouldCaptureDebugLogs() {
        logCaptor0.setLogLevelToDebug();
        LOG.debug("debug message");
        assertThat(logCaptor0.getDebugLogs()).containsExactly("debug message");
    }

    @Test
    void shouldCaptureTraceLogsIfEnabled() {
        LOG.trace("not captured");
        assertThat(logCaptor0.getTraceLogs()).isEmpty();

        logCaptor0.setLogLevelToTrace();
        LOG.trace("got ya");
        assertThat(logCaptor0.getTraceLogs()).containsExactly("got ya");
    }

    @Test
    void shouldProperlySetLogLevelToInfo() {
        logCaptor0.setLogLevel(Level.WARN);
        LOG.info("hidden");
        assertThat(logCaptor0.getInfoLogs()).isEmpty();

        logCaptor0.setLogLevelToInfo();
        LOG.info("revealed");
        assertThat(logCaptor0.getInfoLogs()).containsExactly("revealed");

    }

    @Test
    void shouldCaptureWarnLogs() {
        LOG.warn("warning message");
        assertThat(logCaptor0.getWarnLogs()).containsExactly("warning message");
    }

    @Test
    void shouldCaptureMultipleLogsAtSameLevel() {
        LOG.info("first");
        LOG.info("second");
        LOG.info("third");
        assertThat(logCaptor0.getInfoLogs()).containsExactly("first", "second", "third");
    }

    @Test
    void shouldCaptureAllLogsRegardlessOfLevel() {
        logCaptor0.setLogLevelToTrace();
        LOG.trace("trace");
        LOG.debug("debug");
        LOG.info("info");
        LOG.warn("warn");
        assertThat(logCaptor0.getLogs()).containsExactly("trace", "debug", "info", "warn");
    }

    @Test
    void shouldClearLogsSuccessfully() {
        LOG.info("before clear");
        logCaptor0.clearLogs();
        LOG.info("after clear");
        assertThat(logCaptor0.getInfoLogs()).containsExactly("after clear");
    }

    @Test
    void shouldDisableLogsCompletely() {
        logCaptor0.disableLogs();
        LOG.error("should not appear");
        assertThat(logCaptor0.getLogs()).isEmpty();
    }

    @Test
    void shouldGetLogEventsWithMetadata() {
        LOG.info("test message");
        assertThat(logCaptor0.getLogEvents())
                .hasSize(1)
                .allSatisfy(event -> {
                    assertThat(event.getMessage().getFormattedMessage()).isEqualTo("test message");
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getLoggerName()).isEqualTo(LOG.getName());
                });
    }

    @Test
    void shouldFilterLogsByLevel() {
        logCaptor0.setLogLevelToDebug();
        LOG.trace("trace");
        LOG.debug("debug");
        LOG.info("info");
        LOG.warn("warn");
        assertThat(logCaptor0.getLogs(Level.DEBUG)).containsExactly("debug");
        assertThat(logCaptor0.getLogs(Level.INFO)).containsExactly("info");
        assertThat(logCaptor0.getLogs(Level.WARN)).containsExactly("warn");
    }

    @Test
    void shouldNotCaptureLogsFromOtherLoggers() {
        Logger otherLogger = LogManager.getLogger("other.logger");
        otherLogger.info("from other logger");
        assertThat(logCaptor0.getLogs()).isEmpty();
    }

    @Test
    void captor0ShouldCaptureLogsFrom1and2() {
        LOG.info("0");
        Service1.LOG.info("1");
        Service2.LOG.info("2");

        assertThat(logCaptor0.getInfoLogs()).containsExactly("0", "1", "2");
        assertThat(logCaptor1.getInfoLogs()).containsExactly("1");
        assertThat(logCaptor2.getInfoLogs()).containsExactly("2");
    }

    @Test
    void comCaptorShouldCaptureLogsFromThisTest() {
        try (var captor = LogCaptor.forName("com")) {
            LOG.info("komm!");
            assertThat(captor.getInfoLogs()).containsExactly("komm!");
        }
    }

    @Test
    void comGitCaptorShouldNotCaptureLogsFromThisTest() {
        try (var captor = LogCaptor.forName("com.git")) {
            LOG.info("komm nicht!");
            assertThat(captor.getInfoLogs()).isEmpty();
        }
    }

    @Test
    void rootCaptorShouldCaptureLogsFromThisTest() {
        try (var captor = LogCaptor.forRoot()) {
            LOG.info("hey");
            assertThat(captor.getInfoLogs()).containsExactly("hey");
        }
    }

    @Test
    void captorsShouldNotStepOnEachOthersToesWhenBeingClosed1() {
        try (var captorAbc = LogCaptor.forName("a.b.c")) {
            var logAbc = LogManager.getLogger("a.b.c");

            try (var captorAb = LogCaptor.forName("a.b")) {
                var logAb = LogManager.getLogger("a.b");

                try (var captorA = LogCaptor.forName("a")) {
                    var logA = LogManager.getLogger("a");

                    try (var captorRoot = LogCaptor.forRoot()) {
                        var logOther = LogManager.getLogger("other");

                        logOther.warn("other");
                        assertThat(captorRoot.getWarnLogs()).containsExactly("other");
                    }

                    logA.warn("a");
                    assertThat(captorA.getWarnLogs()).containsExactly("a");
                }

                logAb.warn("ab");
                assertThat(captorAb.getWarnLogs()).containsExactly("ab");
            }

            logAbc.warn("abc");
            assertThat(captorAbc.getWarnLogs()).containsExactly("abc");
        }
    }

    @Test
    void captorsShouldNotStepOnEachOthersToesWhenBeingClosed2() {
        try (var captorAbc = LogCaptor.forName("a.b.c")) {
            var logAbc = LogManager.getLogger("a.b.c");
            logAbc.warn("abc");

            try (var captorAb = LogCaptor.forName("a.b")) {
                var logAb = LogManager.getLogger("a.b");
                logAb.warn("ab");

                try (var captorA = LogCaptor.forName("a")) {
                    var logA = LogManager.getLogger("a");
                    logA.warn("a");

                    try (var captorRoot = LogCaptor.forRoot()) {
                        var logOther = LogManager.getLogger("other");

                        logOther.warn("other");
                        assertThat(captorRoot.getWarnLogs()).containsExactly("other");
                    }

                    assertThat(captorA.getWarnLogs()).containsExactly("a");
                }

                assertThat(captorAb.getWarnLogs()).containsExactly("ab");
            }

            assertThat(captorAbc.getWarnLogs()).containsExactly("abc");
        }
    }

    @Test
    void captorsForIdenticalLoggersShouldNotGetInEachOthersWay() {
        try (var captor1 = LogCaptor.forClass(getClass())) {
            LOG.info("tada");

            try (var captor2 = LogCaptor.forClass(getClass())) {
                LOG.info("trörö");
                assertThat(captor1.getInfoLogs()).containsExactly("tada", "trörö");
                assertThat(captor2.getInfoLogs()).endsWith("trörö");
            }

            LOG.info("huhu");
            assertThat(captor1.getInfoLogs()).containsExactly("tada", "trörö", "huhu");
        }
    }

    @Test
    void interleavedCaptorsForIdenticalLoggersShouldNotCorruptLogLevelOnReset() {
        var log = nextUniqueLogger();
        var initialLevel = log.getLevel();

        try (var captor1 = LogCaptor.forName(log.getName()); var captor2 = LogCaptor.forName(log.getName())) {
            captor1.setLogLevel(Level.WARN);
            assertThat(log.getLevel()).isEqualTo(Level.WARN);
            captor2.setLogLevel(Level.ERROR);
            assertThat(log.getLevel()).isEqualTo(Level.ERROR);
            captor1.resetLogLevel();
            assertThat(log.getLevel()).isEqualTo(initialLevel);
            captor2.resetLogLevel();
            assertThat(log.getLevel()).isEqualTo(initialLevel);
        }
    }

    @Test
    void concurrentlyOperatingCaptorsForSameLoggerShouldNotCorruptLogLevelOnReset() {
        var log = nextUniqueLogger();
        var initialLevel = log.getLevel();

        Runnable changeResetAndCheckLogLevel = () -> {
            try (var captor = LogCaptor.forName(log.getName())) {
                for (int i = 0; i < 1000; i++) {
                    captor.setLogLevelToTrace();
                    log.trace("something to make");
                    captor.setLogLevelToDebug();
                    log.debug("sure that");
                    captor.setLogLevelToInfo();
                    log.info("log level changes");
                    captor.setLogLevelToDebug();
                    log.trace("cannot be optimized");
                    captor.setLogLevelToTrace();
                    log.debug("away by smart JITs");
                    captor.resetLogLevel();
                }
            }
        };

        var jobs = IntStream.range(0, 4)
                .mapToObj(ignore -> CompletableFuture.runAsync(changeResetAndCheckLogLevel))
                .toArray(CompletableFuture<?>[]::new);

        assertThat(CompletableFuture.allOf(jobs)).succeedsWithin(5, TimeUnit.SECONDS);
        assertThat(log.getLevel()).isEqualTo(initialLevel);
    }

    @Test
    void shouldWorkForLogsFromMultipleThreads() {
        var logsPerService = 1000;

        var job1 = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < logsPerService; i++) {
                Service1.LOG.info("Service1: {}", i);
            }
        });

        var job2 = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < logsPerService; i++) {
                Service2.LOG.info("Service2: {}", i);
            }
        });

        assertThat(job1).succeedsWithin(1, TimeUnit.SECONDS);
        assertThat(job2).succeedsWithin(2, TimeUnit.SECONDS);

        assertThat(logCaptor1.getLogEvents())
                .hasSize(logsPerService)
                .isSortedAccordingTo(Comparator.comparingInt(evt -> (Integer) evt.getMessage().getParameters()[0]))
                .allMatch(evt -> evt.getMessage().getFormattedMessage().startsWith("Service1:"));

        assertThat(logCaptor2.getLogEvents())
                .hasSize(logsPerService)
                .isSortedAccordingTo(Comparator.comparingInt(evt -> (Integer) evt.getMessage().getParameters()[0]))
                .allMatch(evt -> evt.getMessage().getFormattedMessage().startsWith("Service2:"));

        assertThat(logCaptor0.getLogEvents())
                .hasSize(2 * logsPerService)
                .containsSubsequence(logCaptor1.getLogEvents())
                .containsSubsequence(logCaptor2.getLogEvents());
    }

    @Test
    void settingAndResettingLogLevelsForRootFromMultipleCaptorsShouldWork() {
        try (var rootCaptor1 = LogCaptor.forRoot(); var rootCaptor2 = LogCaptor.forRoot()) {
            var rootLogger = LogManager.getRootLogger();
            var initialLevel = rootLogger.getLevel();

            rootCaptor1.setLogLevelToTrace();
            assertThat(rootLogger.getLevel()).isEqualTo(Level.TRACE);
            rootCaptor2.setLogLevelToDebug();
            assertThat(rootLogger.getLevel()).isEqualTo(Level.DEBUG);
            rootCaptor2.resetLogLevel();
            assertThat(rootLogger.getLevel()).isEqualTo(initialLevel);
            rootCaptor1.resetLogLevel();
            assertThat(rootLogger.getLevel()).isEqualTo(initialLevel);
        }
    }

    @Test
    void shouldCaptureLogsFromAnonClasses() {
        var test = new Object() {
            void run() {
                var log = LogManager.getLogger(getClass());

                try (var captor = LogCaptor.forClass(getClass())) {
                    log.info("test");
                    assertThat(captor.getInfoLogs()).containsExactly("test");
                }
            }
        };

        test.run();
    }

    @Test
    void shouldRejectNullAsLoggerName() {
        //noinspection resource
        assertThatNullPointerException().isThrownBy(() -> LogCaptor.forName(null));

        //noinspection resource,DataFlowIssue
        assertThatNullPointerException().isThrownBy(() -> LogCaptor.forClass(null));
    }

    @Test
    void shouldWorkWithCustomLevel() {
        var customLevel = Level.forName("CUSTOM", 401);
        logCaptor0.setLogLevel(customLevel);
        LOG.log(customLevel, "test");
        assertThat(logCaptor0.getLogs(customLevel)).containsExactly("test");
        logCaptor0.resetLogLevel();
        assertThat(LOG.getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void shouldRestorePreviousStateOnResetIfLogLevelIsInherited() {
        var log = LogManager.getLogger("configured.via.root.logger");
        try (var rootCaptor = LogCaptor.forRoot(); var captor = LogCaptor.forName(log.getName())) {
            captor.setLogLevel(Level.WARN);
            captor.resetLogLevel();

            rootCaptor.setLogLevel(Level.DEBUG);
            try {
                log.debug("should pick up debug level from root");
                assertThat(captor.getDebugLogs()).hasSize(1);
            } finally {
                rootCaptor.resetLogLevel();
            }
        }
    }

    @Test
    void shouldRestorePreviousStateIfLoggerIsConfiguredWithoutLogLevel() {
        var log = LogManager.getLogger("some.package.no.level");
        var initialLevel = log.getLevel();
        var context = LoggerContext.getContext(false);
        try (var captor = LogCaptor.forName(log.getName())) {
            captor.setLogLevel(Level.ERROR);
            assertThat(log.getLevel()).isEqualTo(Level.ERROR);
            assertThat(context.getConfiguration().getLoggerConfig(log.getName()).getExplicitLevel()).isEqualTo(Level.ERROR);
            captor.resetLogLevel();
            assertThat(log.getLevel()).isEqualTo(initialLevel);
            assertThat(context.getConfiguration().getLoggerConfig(log.getName()).getExplicitLevel()).isNull();
        }
    }

    @Test
    void shouldRestorePreviousStateIfLoggerIsConfiguredWithWarnLevel() {
        var log = LogManager.getLogger("some.package.warn.level");
        var initialLevel = log.getLevel();
        var context = LoggerContext.getContext(false);
        try (var captor = LogCaptor.forName(log.getName())) {
            captor.setLogLevel(Level.ERROR);
            assertThat(log.getLevel()).isEqualTo(Level.ERROR);
            assertThat(context.getConfiguration().getLoggerConfig(log.getName()).getExplicitLevel()).isEqualTo(Level.ERROR);
            captor.resetLogLevel();
            assertThat(log.getLevel()).isEqualTo(initialLevel);
            assertThat(context.getConfiguration().getLoggerConfig(log.getName()).getExplicitLevel()).isEqualTo(initialLevel);
        }
    }

    @Test
    void settingAndResettingParentLogLevelShouldAffectChildren() {
        var parentLogger = LogManager.getLogger("parent");
        var childLogger = LogManager.getLogger("parent.child");

        try (var parentCaptor = LogCaptor.forName(parentLogger.getName()); var childCaptor = LogCaptor.forName(childLogger.getName())) {
            parentCaptor.setLogLevel(Level.ERROR);
            childLogger.info("filtered");
            assertThat(childCaptor.getLogs()).isEmpty();
            assertThat(childLogger.getLevel()).isEqualTo(Level.ERROR);

            parentCaptor.resetLogLevel();
            childLogger.info("not filtered");
            assertThat(childCaptor.getLogs()).hasSize(1);
            assertThat(childLogger.getLevel()).isEqualTo(Level.INFO);
        }
    }

    @Test
    void doubleCloseShouldNotThrow() {
        assertThatNoException().isThrownBy(() -> {
            logCaptor0.close();
            logCaptor0.close();
        });
    }

    static final AtomicLong loggerSequence = new AtomicLong();

    static Logger nextUniqueLogger() {
        return LogManager.getLogger(LogCaptorTest.class.getName() + ".uniq" + loggerSequence.getAndIncrement());
    }
}
