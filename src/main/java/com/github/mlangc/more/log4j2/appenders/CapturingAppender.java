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
package com.github.mlangc.more.log4j2.appenders;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;

import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Plugin(name = "Captor", category = Node.CATEGORY, elementType = Appender.ELEMENT_TYPE, printObject = true)
public class CapturingAppender extends AbstractAppender {
    private final Deque<LogEvent> capturedLogEvents = new ConcurrentLinkedDeque<>();
    private final Set<String> monitoredLoggers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    protected CapturingAppender(String name) {
        super(name, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        capturedLogEvents.add(event.toImmutable());
    }

    @PluginFactory
    public static CapturingAppender create(@Required @PluginAttribute(value = "name") String name) {
        return new CapturingAppender(name);
    }

    public Collection<LogEvent> capturedLogEvents() {
        return Collections.unmodifiableCollection(capturedLogEvents);
    }

    public void startMonitoringLogger(Logger logger) {
        monitoredLoggers.add(logger.getName());
    }

    public void stopMonitoringLogger(Logger logger) {
        if (monitoredLoggers.remove(logger.getName())) {
            clearLogs(logger);
        }
    }

    public void clearLogs(Logger logger) {
        capturedLogEvents.removeIf(evt -> logger.getName().equals(evt.getLoggerName()));
    }
}
