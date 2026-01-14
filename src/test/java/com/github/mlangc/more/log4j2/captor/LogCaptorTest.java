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
}