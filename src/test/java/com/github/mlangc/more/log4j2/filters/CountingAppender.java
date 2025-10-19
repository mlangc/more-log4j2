package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

import java.util.concurrent.atomic.LongAdder;

@Plugin(name = "CountingAppender", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE, printObject = true)
public class CountingAppender extends AbstractAppender {
    private final LongAdder numEvents = new LongAdder();

    protected CountingAppender(String name) {
        super(name, null, null, false, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        numEvents.increment();
    }

    @PluginFactory
    public static CountingAppender create(@PluginAttribute("name") String name) {
        return new CountingAppender(name);
    }

    long currentCount() {
        return numEvents.longValue();
    }

    void clear() {
        numEvents.reset();
    }
}
