package com.github.mlangc.more.log4j2.junit;

import org.apache.logging.log4j.core.util.ShutdownCallbackRegistry;
import org.apache.logging.log4j.util.BiConsumer;
import org.apache.logging.log4j.util.PropertySource;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public class ShutdownHookDisablingPropertySource implements PropertySource {
    private static final String PREFIX = "log4j.";

    private final Map<String, String> properties = Collections
            .singletonMap(ShutdownCallbackRegistry.SHUTDOWN_HOOK_ENABLED, "false");

    @Override
    public void forEach(BiConsumer<String, String> action) {
        this.properties.forEach(action::accept);
    }

    @Override
    public CharSequence getNormalForm(Iterable<? extends CharSequence> tokens) {
        return PREFIX + Util.joinAsCamelCase(tokens);
    }

    @Override
    public int getPriority() {
        return -200;
    }

    @Override
    public String getProperty(String key) {
        return this.properties.get(key);
    }

    @Override
    public boolean containsProperty(String key) {
        return this.properties.containsKey(key);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return this.properties.keySet();
    }
}
