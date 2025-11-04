/*-
 * #%L
 * more-log4j2
 * %%
 * Copyright (C) 2025 Matthias Langer
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
package com.github.mlangc.more.log4j2.test.helpers;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.spi.ExtendedLogger;

import java.net.URI;
import java.net.URISyntaxException;

public class TestHelpers {
    public static final TestException TEST_EXCEPTION = new TestException();

    @SuppressWarnings("serial")
    public static class TestException extends RuntimeException {
        TestException() {
            super(null, null, true, false);
        }
    }

    public static int logWithAllOverloads(ExtendedLogger log, Marker marker, String message) {
        int numLogs = 0;

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

    public static LoggerContext loggerContextFromTestResource(String path) {
        try {
            URI uri = LoggerContext.class.getClassLoader().getResource(path).toURI();
            return LoggerContext.getContext(LoggerContext.class.getClassLoader(), false, uri);
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("Error loading '" + path + "' from class path", e);
        }
    }
}
