package com.github.mlangc.more.log4j2.demos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

class MarkerBasedLogThrottlingDemo {
    private static final Logger LOG = LoggerFactory.getLogger(MarkerBasedLogThrottlingDemo.class);

    public static final Marker THROTTLED_1 = MarkerFactory.getMarker("throttled1");
    public static final Marker THROTTLED_10 = MarkerFactory.getMarker("throttled10");

    static void main() {
        for (int i = 0; i < 10000; i++) {
            LOG.info(THROTTLED_1, "something={}", i);
            LOG.info(THROTTLED_10, "something else={}", i);
        }

        LOG.info("and by the way....");
    }
}
