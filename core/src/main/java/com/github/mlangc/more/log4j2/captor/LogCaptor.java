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
import java.util.stream.Stream;

public class LogCaptor implements AutoCloseable {
    private final CapturingAppender capturingAppender;
    private final Logger logger;
    private final Level originalLevel;

    private LogCaptor(String loggerName) {
        this.logger = LogManager.getLogger(loggerName);
        this.capturingAppender = getCapturingAppender();
        this.originalLevel = LogManager.getLogger(loggerName).getLevel();
        capturingAppender.startMonitoringLogger(logger);
    }

    private static CapturingAppender getCapturingAppender() {
        var capturingAppenders = new ArrayList<CapturingAppender>(1);
        for (var appender : LoggerContext.getContext(false).getConfiguration().getAppenders().values()) {
            if (appender instanceof CapturingAppender capturingAppender) {
                capturingAppenders.add(capturingAppender);
            }
        }

        if (capturingAppenders.isEmpty()) {
            throw new IllegalArgumentException("TODO");
        } else if (capturingAppenders.size() > 1) {
            throw new IllegalArgumentException("TODO");
        }

        return capturingAppenders.get(0);
    }

	public static LogCaptor forName(String loggerName) {
		return new LogCaptor(loggerName);
	}

    public static LogCaptor forClass(Class<?> clazz) {
        return new LogCaptor(clazz.getName());
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

    public List<String> getLogs(Level level) {
        return streamLogs()
                .filter(e -> e.getLevel() == level)
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    public List<String> getLogs() {
        return streamLogs()
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    public List<LogEvent> getLogEvents() {
        return streamLogs().toList();
    }

    public void setLogLevel(Level level) {
        Configurator.setLevel(logger, level);
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

    private Stream<LogEvent> streamLogs() {
        return capturingAppender.capturedLogEvents().stream().filter(e -> logger.getName().equals(e.getLoggerName()));
    }

    @Override
    public void close() {
        capturingAppender.stopMonitoringLogger(logger);
        resetLogLevel();
    }

    public void clearLogs() {
        capturingAppender.clearLogs(logger);
    }

    public void disableLogs() {
        Configurator.setLevel(logger, Level.OFF);
    }

    public void resetLogLevel() {
        if (logger.getLevel() != originalLevel) {
            Configurator.setLevel(logger, originalLevel);
        }
    }
}
