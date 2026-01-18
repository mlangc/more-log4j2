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

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

@Plugin(name = "Captor", category = Node.CATEGORY, elementType = Appender.ELEMENT_TYPE, printObject = true)
public class CapturingAppender extends AbstractAppender {
    private final Set<LogEventConsumer> consumers = Collections.newSetFromMap(new WeakHashMap<>());

    protected CapturingAppender(String name, Filter filter) {
        // TODO: Filter support
        super(name, filter, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public synchronized void append(LogEvent event) {
        LogEvent immutableEvent = null;
        for (var consumer : consumers) {
            if (consumer.isInterested(event)) {
                if (immutableEvent == null) {
                    immutableEvent = event.toImmutable();
                }

                consumer.consume(immutableEvent);
            }
        }
    }

    @PluginFactory
    public static CapturingAppender create(
            @Required @PluginAttribute(value = "name") String name,
            @PluginElement("Filter") Filter filter) {
        return new CapturingAppender(name, filter);
    }

    public interface LogEventConsumer {
        boolean isInterested(LogEvent event);
        void consume(LogEvent event);
    }

    public synchronized void registerConsumer(LogEventConsumer consumer) {
        consumers.add(consumer);
    }

    public synchronized void unregisterConsumer(LogEventConsumer consumer) {
        consumers.remove(consumer);
    }
}
