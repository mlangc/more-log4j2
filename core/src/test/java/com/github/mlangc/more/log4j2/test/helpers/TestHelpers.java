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
package com.github.mlangc.more.log4j2.test.helpers;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.spi.ExtendedLogger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

public class TestHelpers {
    public static final TestException TEST_EXCEPTION = new TestException();

    @SuppressWarnings("serial")
    public static class TestException extends RuntimeException {
        TestException() {
            super(null, null, true, false);
        }
    }

    public static int logWithAllOverloads(ExtendedLogger log, String message) {
        return logWithAllOverloads(log, null, message);
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

    public static Map<Filter.Result, Integer> filterWithAllOverloads(Filter filter, ExtendedLogger log, Level level, Marker marker, String message) {
        Map<Filter.Result, Integer> results = new EnumMap<>(Filter.Result.class);
        for (Filter.Result result : Filter.Result.values()) {
            results.put(result, 0);
        }

        Consumer<Filter.Result> processResult = r -> results.merge(r, 1, Integer::sum);

        Logger logger = (Logger) log;
        Log4jLogEvent event = new Log4jLogEvent(log.getName(), marker, "org.apache.logging.log4j.spi.AbstractLogger", null, level, new SimpleMessage("test"), null, null);

        processResult.accept(filter.filter(event));
        processResult.accept(filter.filter(logger, level, marker, message));
        processResult.accept(filter.filter(logger, level, marker, "{} - 0", message));
        processResult.accept(filter.filter(logger, level, marker, "{} - 0 - {}", message, 1));
        processResult.accept(filter.filter(logger, level, marker, "{} - 0 - {} - {}", message, 1, 2));
        processResult.accept(filter.filter(logger, level, marker, "{} - 0 - {} - {} - {}", message, 1, 2, 3));
        processResult.accept(filter.filter(logger, level, marker, "{} - 0 - {} - {} - {} - {}", message, 1, 2, 3, 4));
        processResult.accept(filter.filter(logger, level, marker, "{} - 0 - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5));
        processResult.accept(filter.filter(logger, level, marker, "{} - 0 - {} - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5, 6));
        processResult.accept(filter.filter(logger, level, marker, "{} - 0 - {} - {} - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5, 6, 7));
        processResult.accept(filter.filter(logger, level, marker, "{} - 0 - {} - {} - {} - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5, 6, 7, 8));
        processResult.accept(filter.filter(logger, level, marker, "{} - 0 - {} - {} - {} - {} - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5, 6, 7, 8, 9));
        processResult.accept(filter.filter(logger, level, marker, "{} - 0 - {} - {} - {} - {} - {} - {} - {} - {} - {} - {}", message, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        processResult.accept(filter.filter(logger, level, marker, (Object) message, TEST_EXCEPTION));
        processResult.accept(filter.filter(logger, level, marker, new SimpleMessage(message), TEST_EXCEPTION));

        return results;
    }

    public static LoggerContext loggerContextFromTestResource(String path) {
        try {
            URI uri = LoggerContext.class.getClassLoader().getResource(path).toURI();
            return LoggerContext.getContext(LoggerContext.class.getClassLoader(), false, uri);
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("Error loading '" + path + "' from class path", e);
        }
    }

    public static LoggerContext loggerContextFromConfig(ConfigurationBuilder<?> configurationBuilder) {
        try {
            assertThat(configurationBuilder.isValid()).isTrue();
            return loggerContextFromConfig(configurationBuilder.build(false));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot initialize configuration:" + configurationBuilder, e);
        }
    }

    public static LoggerContext loggerContextFromConfig(Configuration configuration) {
        LoggerContext context = Configurator.initialize(configuration);
        if (context.getConfiguration() != configuration) {
            // TODO: Figure out why this seems to be sometimes necessary in tests
            context.reconfigure(configuration);
        }

        assertThat(context.getConfiguration()).isSameAs(configuration);
        return context;
    }

    public static <T extends Appender> T findAppender(LoggerContext context, Class<T> appenderClass) {
        return findAppender(context.getConfiguration(), appenderClass);
    }

    public static <T extends Appender> T findAppender(Configuration configuration, Class<T> appenderClass) {
        var candidates = new ArrayList<T>();
        for (var appender : configuration.getAppenders().values()) {
            if (appenderClass.isInstance(appender)) {
                candidates.add(appenderClass.cast(appender));
            }
        }

        if (candidates.isEmpty()) {
            throw new RuntimeException("Could not find appender of type " + appenderClass.getCanonicalName());
        } else if (candidates.size() > 1) {
            throw new RuntimeException("Found multiple appenders of type " + appenderClass.getCanonicalName() + ": " + candidates);
        }

        return candidates.get(0);
    }

    public static void withAlternativeLoggerContext(ConfigurationBuilder<?> configBuilder, Consumer<LoggerContext> op) {
        try (var context = loggerContextFromConfig(configBuilder)) {
            op.accept(context);
        }
    }
}
