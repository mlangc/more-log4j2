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
package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.util.PerformanceSensitive;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;

@Plugin(name = "ThrottlingFilter", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE, printObject = true)
@PerformanceSensitive("allocation")
public class ThrottlingFilter extends AbstractFilter {
    private final Level level;
    private final long intervalNanos;
    private final long maxEvents;
    private final Ticker ticker;

    private final AtomicLong startTicks;
    private final AtomicLong eventCounter;

    ThrottlingFilter(Result onMatch, Result onMismatch, Level level, long intervalNanos, long maxEvents) {
        this(onMatch, onMismatch, level, intervalNanos, maxEvents, Ticker.SYSTEM);
    }

    ThrottlingFilter(Result onMatch, Result onMismatch, Level level, long intervalNanos, long maxEventsPerInterval, Ticker ticker) {
        super(onMatch, onMismatch);

        this.level = level;
        this.intervalNanos = intervalNanos;
        this.maxEvents = maxEventsPerInterval;
        this.ticker = ticker;
        this.eventCounter = new AtomicLong();

        // Note: We could just initialize `startTicks` with `ticker.currentTicks()`, however, aligning intervals along multiples
        // of `intervalNanos` makes the behaviour of the filter more predictable for tests.
        this.startTicks = new AtomicLong(Math.floorDiv(ticker.currentTicks(), intervalNanos) * intervalNanos);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return filter(level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg) {
        return filter(level);
    }

    @Override
    public Result filter(LogEvent event) {
        return filter(event.getLevel());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return filter(level);
    }

    @PluginFactory
    public static ThrottlingFilter create(
            @PluginAttribute("onMatch") Result onMatch,
            @PluginAttribute("onMismatch") Result onMismatch,
            @PluginAttribute("level") Level level,
            @PluginAttribute("interval") Long interval,
            @PluginAttribute("timeUnit") TimeUnit timeUnit,
            @PluginAttribute("maxEvents") Long maxEvents) {
        return new ThrottlingFilter(
                onMatch == null ? Result.NEUTRAL : onMatch,
                onMismatch == null ? Result.DENY : onMismatch,
                level == null ? Level.WARN : level,
                timeUnit.toNanos(interval),
                requireNonNull(maxEvents)
        );
    }

    private Result filter(Level level) {
        if (!this.level.isMoreSpecificThan(level)) {
            return onMatch;
        }

        long t = ticker.currentTicks();
        while (true) {
            long t0 = startTicks.get();
            if (t0 + intervalNanos > t) {
                if (eventCounter.get() >= maxEvents) {
                    return onMismatch;
                }

                return eventCounter.incrementAndGet() <= maxEvents ? onMatch : onMismatch;
            }

            long intoNewInterval = (t - t0) % intervalNanos;
            long newStartTicks = t - intoNewInterval;
            if (startTicks.compareAndSet(t0, newStartTicks)) {
                // Note: This update is racy — another thread may increment eventCounter after the compareAndSet
                // but before the set below. As a result, some events may not be counted, allowing a few extra
                // logs through. Fixing this would require treating startTicks and eventCounter as
                // a single atomic unit, e.g., with an AtomicReference<State> or a lock. Both alternatives have
                // downsides: object churn or added overhead. Given the rarity and minor impact of this race,
                // allowing occasional extra logs is an acceptable trade-off for performance.
                eventCounter.set(1);
                return onMatch;
            }
        }
    }

    long intervalNanos() {
        return intervalNanos;
    }

    long maxEvents() {
        return maxEvents;
    }

    @Override
    public String toString() {
        return "ThrottlingFilter{" +
               "level=" + level +
               ", interval=" + Duration.ofNanos(intervalNanos) +
               ", eventsPerInterval=" + maxEvents +
               ", onMatch=" + onMatch +
               ", onMismatch=" + onMismatch +
               '}';
    }
}
