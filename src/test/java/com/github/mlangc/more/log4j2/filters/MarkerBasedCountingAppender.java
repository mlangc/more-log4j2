package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

@Plugin(name = "MarkerBasedCountingAppender", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE, printObject = true)
public class MarkerBasedCountingAppender extends AbstractAppender {
    private final LongAdder numEventsWithoutMarker = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> numEventsWithMarker = new ConcurrentHashMap<>();

    protected MarkerBasedCountingAppender(String name) {
        super(name, null, null, false, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        if (event.getMarker() == null) {
            numEventsWithoutMarker.increment();
        } else {
            numEventsWithMarker.computeIfAbsent(event.getMarker().getName(), ignore -> new LongAdder()).increment();
        }
    }

    @PluginFactory
    public static MarkerBasedCountingAppender create(@PluginAttribute("name") String name) {
        return new MarkerBasedCountingAppender(name);
    }

    long numEventsWithoutMarker() {
        return numEventsWithoutMarker.longValue();
    }

    Map<String, Long> numEventsWithMarker() {
        return numEventsWithMarker.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().longValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
