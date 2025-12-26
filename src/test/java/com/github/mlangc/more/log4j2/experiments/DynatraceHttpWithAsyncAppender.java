package com.github.mlangc.more.log4j2.experiments;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DynatraceHttpWithAsyncAppender {
    private static final Logger LOG;

    static {
        System.setProperty("log4j2.is.webapp", "false");
        System.setProperty("log4j2.configurationFile", "DynatraceHttpAsyncAppenderConfig.xml");

        LOG = LogManager.getLogger(DynatraceHttpWithAsyncAppender.class);
    }

    public static void main(String[] args) {
        for (int i = 0; i < 25; i++) {
            LOG.info("lauser: {}", i);
        }
    }
}
