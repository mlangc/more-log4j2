package com.github.mlangc.more.log4j2.junit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

class AsyncHttpAppenderFlushingTestExecutionListenerTest {
    private static final Logger LOG = LogManager.getLogger(AsyncHttpAppenderFlushingTestExecutionListenerTest.class);

    @Test
    void shouldFlushAppender() {
        LOG.info("Test");
    }
}