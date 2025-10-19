package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.AbstractLifeCycle;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.message.Message;

@Plugin(name = "AcceptAllFilter", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE, printObject = true)
public class AcceptAllFilter extends AbstractLifeCycle implements Filter {
    @Override
    public Result getOnMismatch() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Result getOnMatch() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return Result.ACCEPT;
    }

    @Override
    public Result filter(LogEvent event) {
        return Result.ACCEPT;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    @PluginFactory
    public static AcceptAllFilter create() {
        return new AcceptAllFilter();
    }
}
