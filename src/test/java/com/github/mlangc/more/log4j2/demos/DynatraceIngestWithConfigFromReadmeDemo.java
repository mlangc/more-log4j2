package com.github.mlangc.more.log4j2.demos;

import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;

public class DynatraceIngestWithConfigFromReadmeDemo {
    public static void main(String[] args) {
        try (var context = TestHelpers.loggerContextFromTestResource("DynatraceIngestReadmeConfig.xml")) {
            var log = context.getLogger(DynatraceIngestWithConfigFromReadmeDemo.class);
            log.info("hello");
            log.info("world");
        }
    }
}
