package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.spi.ExtendedLogger;

class TestHelpers {
    static final TestException TEST_EXCEPTION = new TestException();

    static class TestException extends RuntimeException {
        TestException() {
            super(null, null, true, false);
        }
    }

    static int logWithAllOverloads(ExtendedLogger log, Marker marker, String message) {
        var numLogs = 0;

        log.info(marker, message);
        numLogs++;

        log.info(marker, "{} - 0", message);
        numLogs++;

        log.info(marker, "{} - 0 - {}", message, 1);
        numLogs++;

        log.info(marker, "{} - 0 - {} - {}", message, 1, 2);
        numLogs++;

        log.info(marker, "{} - 0 - {} - {} - {}", message, 1, 2, 3);
        numLogs++;

        log.info(marker, "{} - 0 - {} - {} - {} - {}", message, 1, 2, 3, 4);
        numLogs++;

        log.info(marker, "{} - 0 - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5);
        numLogs++;

        log.info(marker, "{} - 0 - {} - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5, 6);
        numLogs++;

        log.info(marker, "{} - 0 - {} - {} - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5, 6, 7);
        numLogs++;

        log.info(marker, "{} - 0 - {} - {} - {} - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5, 6, 7, 8);
        numLogs++;

        log.info(marker, "{} - 0 - {} - {} - {} - {} - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        numLogs++;

        log.info(marker, "{} - 0 - {} - {} - {} - {} - {} - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        numLogs++;

        log.info(marker, message, TEST_EXCEPTION);
        numLogs++;

        log.info(marker, (Message) new SimpleMessage(message));
        numLogs++;

        log.info(marker, (Message) new SimpleMessage(message), TEST_EXCEPTION);
        numLogs++;

        log.logIfEnabled(log.getName(), Level.INFO, marker, message);
        numLogs++;

        return numLogs;
    }
}
