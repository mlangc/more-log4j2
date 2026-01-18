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

import com.github.mlangc.more.log4j2.appenders.CapturingAppender;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.github.mlangc.more.log4j2.internal.util.LoggerNameUtil.isNameEqualOrAncestorOf;

public class LogCaptor implements AutoCloseable {
    private final CapturingAppender capturingAppender;
    private final ArrayList<LogEvent> capturedEvents = new ArrayList<>();

    private final Logger logger;
    private final Logger altLogger;
    private final Level originalLevel;
    private final CapturingAppender.LogEventConsumer logsConsumer;

    private LogCaptor(String loggerName, String altLoggerName) {
        this.logger = LogManager.getLogger(loggerName);
        this.altLogger = (altLoggerName != null && !loggerName.equals(altLoggerName)) ? LogManager.getLogger(altLoggerName) : null;
        this.capturingAppender = getCapturingAppender();
        this.originalLevel = LogManager.getLogger(loggerName).getLevel();

        this.logsConsumer = new CapturingAppender.LogEventConsumer() {
            @Override
            public boolean isInterested(LogEvent event) {
                if (isNameEqualOrAncestorOf(logger.getName(), event.getLoggerName())) {
                    return true;
                }

                if (altLogger != null && isNameEqualOrAncestorOf(altLogger.getName(), event.getLoggerName())) {
                    return true;
                }

                return false;
            }

            @Override
            public void consume(LogEvent event) {
                synchronized (LogCaptor.this) {
                    capturedEvents.add(event);
                }
            }
        };
    }

    private static CapturingAppender getCapturingAppender() {
        var capturingAppenders = new ArrayList<CapturingAppender>(1);
        for (var appender : LoggerContext.getContext(false).getConfiguration().getAppenders().values()) {
            if (appender instanceof CapturingAppender capturingAppender) {
                capturingAppenders.add(capturingAppender);
            }
        }

        if (capturingAppenders.isEmpty()) {
            throw new IllegalStateException("Cannot find `CapturingAppender`; please check the README.MD and adapt your log4j2-test.xml");
        } else if (capturingAppenders.size() > 1) {
            throw new IllegalStateException("Found multiple `CapturingAppender` instances " + capturingAppenders + "; please check the README.MD and adapt your log4j2-test.xml");
        }

        return capturingAppenders.get(0);
    }

	public static LogCaptor forName(String loggerName) {
		return forName(loggerName, null);
	}

    private static LogCaptor forName(String loggerName, String altLoggerName) {
        var captor = new LogCaptor(loggerName, altLoggerName);
        captor.capturingAppender.registerConsumer(captor.logsConsumer);
        return captor;
    }

    public static LogCaptor forClass(Class<?> clazz) {
        // Note: SLF4J uses getName(), but Log4j2 uses getCannonicalName() if used directly.
        // In order to reliably capture logs, we therefore simply use both names.
        return forName(clazz.getCanonicalName(), clazz.getName());
    }

    public static LogCaptor forRoot() {
        return forName(LogManager.ROOT_LOGGER_NAME);
    }

    public List<String> getTraceLogs() {
        return getLogs(Level.TRACE);
    }

    public List<String> getDebugLogs() {
        return getLogs(Level.DEBUG);
    }

    public List<String> getInfoLogs() {
        return getLogs(Level.INFO);
    }

    public List<String> getWarnLogs() {
        return getLogs(Level.WARN);
    }

	public List<String> getErrorLogs() {
		return getLogs(Level.ERROR);
	}

    public synchronized List<String> getLogs(Level level) {
            return capturedEvents.stream()
                    .filter(e -> e.getLevel() == level)
                    .map(e -> e.getMessage().getFormattedMessage())
                    .toList();
    }

    public synchronized List<String> getLogs() {
        return capturedEvents.stream()
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    public synchronized List<LogEvent> getLogEvents() {
        return List.copyOf(capturedEvents);
    }

    public void setLogLevel(Level level) {
        doForLoggers(logger -> Configurator.setLevel(logger, level));
    }

    public void setLogLevelToInfo() {
        setLogLevel(Level.INFO);
    }

    public void setLogLevelToDebug() {
        setLogLevel(Level.DEBUG);
    }

    public void setLogLevelToTrace() {
        setLogLevel(Level.TRACE);
    }

    @Override
    public void close() {
        capturingAppender.unregisterConsumer(logsConsumer);
        resetLogLevel();
        clearLogs();
    }

    public synchronized void clearLogs() {
        capturedEvents.clear();
    }

    public void disableLogs() {
        doForLoggers(logger -> Configurator.setLevel(logger, Level.OFF));
    }

    public void resetLogLevel() {
        doForLoggers(logger -> {
            if (logger.getLevel() != originalLevel) {
                Configurator.setLevel(logger, originalLevel);
            }
        });
    }

    private void doForLoggers(Consumer<Logger> op) {
        op.accept(logger);
        if (altLogger != null) op.accept(altLogger);
    }
}
