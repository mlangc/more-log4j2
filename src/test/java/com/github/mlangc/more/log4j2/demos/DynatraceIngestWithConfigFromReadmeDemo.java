package com.github.mlangc.more.log4j2.demos;

import com.github.mlangc.more.log4j2.experiments.DynatraceHttpWithAsyncAppender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DynatraceIngestWithConfigFromReadmeDemo {
    private static final Logger LOG;

    static {
        System.setProperty("log4j2.shutdownHookEnabled", "true");
        System.setProperty("log4j2.configurationFile", "DynatraceIngestReadmeConfig.xml");

        LOG = LogManager.getLogger(DynatraceHttpWithAsyncAppender.class);
    }


    public static void main(String[] args) {
        LOG.info("hello");
        LOG.info("world");
    }
}
