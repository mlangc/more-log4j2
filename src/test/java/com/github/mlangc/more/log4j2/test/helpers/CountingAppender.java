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
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@Plugin(name = "CountingAppender", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE)
public class CountingAppender extends AbstractAppender {
    private final LongAdder numEvents = new LongAdder();
    private final LongAdder numEventsWithoutMarker = new LongAdder();
    private final ConcurrentHashMap<String, Long> numEventsWithMarker = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Level, Long> numEventsWithLevel = new ConcurrentHashMap<>();

    protected CountingAppender(String name) {
        super(name, null, null, false, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        numEvents.increment();
        numEventsWithLevel.merge(event.getLevel(), 1L, Long::sum);

        if (event.getMarker() == null) {
            numEventsWithoutMarker.increment();
        } else {
            numEventsWithMarker.merge(event.getMarker().getName(), 1L, Long::sum);
        }
    }

    @PluginFactory
    public static CountingAppender create(@PluginAttribute("name") String name) {
        return new CountingAppender(name);
    }

    public long currentCount() {
        return numEvents.longValue();
    }

    public long currentCountWithoutMarker() {
        return numEventsWithoutMarker.longValue();
    }

    public Map<String, Long> currentCountsWithMarkers() {
        return Collections.unmodifiableMap(numEventsWithMarker);
    }

    public Map<Level, Long> currentCountsWithLevels() {
        return Collections.unmodifiableMap(numEventsWithLevel);
    }

    public void clear() {
        numEvents.reset();
        numEventsWithoutMarker.reset();
        numEventsWithMarker.clear();
    }
}
