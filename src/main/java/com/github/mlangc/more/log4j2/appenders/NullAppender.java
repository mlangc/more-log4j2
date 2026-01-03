package com.github.mlangc.more.log4j2.appenders;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;

@Plugin(name = "Null", category = Node.CATEGORY, elementType = Appender.ELEMENT_TYPE, printObject = true)
public class NullAppender extends AbstractAppender {
    protected NullAppender(String name) {
        super(name, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        // Does nothing by design
    }

    @Override
    public String toString() {
        return "NullAppender{" +
               "name='" + getName() + '\'' +
               ", state=" + getState() +
               '}';
    }

    @PluginFactory
    public static NullAppender create(@Required @PluginAttribute(value = "name") String name) {
        return new NullAppender(name);
    }
}
