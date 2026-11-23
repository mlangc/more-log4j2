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
import org.apache.logging.log4j.core.util.NanoClock;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.util.PerformanceSensitive;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Plugin(name = "ThrottlingFilter", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE, printObject = true)
@PerformanceSensitive("allocation")
public class ThrottlingFilter extends AbstractFilter {
    private final Throttler throttler;
    private final Level level;

    ThrottlingFilter(Result onMatch, Result onMismatch, Level level, long intervalNanos, long maxEvents) {
        this(onMatch, onMismatch, level, intervalNanos, maxEvents, System::nanoTime);
    }

    ThrottlingFilter(Result onMatch, Result onMismatch, Level level, long intervalNanos, long maxEvents, NanoClock nanoClock) {
        super(onMatch, onMismatch);

        this.level = level;
        this.throttler = new BestEffortThrottler(intervalNanos, maxEvents, nanoClock);
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
                maxEvents
        );
    }

    private Result filter(Level level) {
        if (!this.level.isMoreSpecificThan(level)) {
            return onMatch;
        }

        return throttler.throttle() ? onMismatch : onMatch;
    }

    long intervalNanos() {
        return throttler.intervalNanos;
    }

    long maxEvents() {
        return throttler.maxEvents;
    }

    @Override
    public String toString() {
        return "ThrottlingFilter{" +
               "level=" + level +
               ", interval=" + Duration.ofNanos(throttler.intervalNanos) +
               ", eventsPerInterval=" + throttler.maxEvents +
               ", onMatch=" + onMatch +
               ", onMismatch=" + onMismatch +
               '}';
    }

    static abstract class Throttler {
        final long intervalNanos;
        final long maxEvents;
        final NanoClock nanoClock;

        Throttler(long intervalNanos, long maxEvents, NanoClock nanoClock) {
            if (intervalNanos <= 0) {
                throw new IllegalArgumentException("interval must be positive");
            }

            if (maxEvents <= 0) {
                throw new IllegalArgumentException("maxEvents must be positive");
            }

            this.intervalNanos = intervalNanos;
            this.maxEvents = maxEvents;
            this.nanoClock = nanoClock;
        }

        abstract boolean throttle();

        long startOfPreviousInterval() {
            // Note: We could just initialize `startTicks` with `ticker.currentTicks()`, however, aligning intervals along multiples
            // of `intervalNanos` makes the behaviour of the filter more predictable for tests.
            return Math.floorDiv(nanoClock.nanoTime(), intervalNanos) * intervalNanos;
        }
    }

    static class BestEffortThrottler extends Throttler {
        final AtomicLong startTicks;
        final AtomicLong eventCounter = new AtomicLong();

        BestEffortThrottler(long intervalNanos, long maxEvents, NanoClock nanoClock) {
            super(intervalNanos, maxEvents, nanoClock);
            this.startTicks = new AtomicLong(startOfPreviousInterval());
        }

        @Override
        boolean throttle() {
            // Note: `t` is read once, before the CAS loop. Correctness-wise either position is defensible:
            // reading inside the loop gives a `t` slightly closer to when the log event's own timestamp will
            // be captured (which happens after this method returns), but reading outside better reflects when
            // the call was issued. Performance clearly favors reading once.
            long t = nanoClock.nanoTime();
            while (true) {
                long t0 = startTicks.get();

                if (t < t0) {
                    t = nanoClock.nanoTime();
                }

                if (t - t0 < intervalNanos) {
                    if (eventCounter.get() >= maxEvents) {
                        return true;
                    }

                    return eventCounter.incrementAndGet() > maxEvents;
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
                    return false;
                }
            }
        }
    }

    static class SynchronizingThrottler extends Throttler {
        long startNanos;
        long eventCounter;

        SynchronizingThrottler(long intervalNanos, long maxEvents, NanoClock nanoClock) {
            super(intervalNanos, maxEvents, nanoClock);

            this.startNanos = startOfPreviousInterval();
        }

        @Override
        boolean throttle() {
            while (true) {
                long t = nanoClock.nanoTime();

                synchronized (this) {
                    if (t < startNanos) {
                        // This can happen if another thread manages to advance startNanos before this thread acquires the lock.
                        continue;
                    }

                    if (t - startNanos < intervalNanos) {
                        if (eventCounter >= maxEvents) {
                            return true;
                        }

                        return ++eventCounter > maxEvents;
                    } else {
                        long intoNewIntervalNanos = (t - startNanos) % intervalNanos;
                        startNanos = t - intoNewIntervalNanos;
                        eventCounter = 1;
                        return false;
                    }
                }
            }
        }
    }

    /**
     * Lock-free throttler without the rollover race of {@link BestEffortThrottler}: the index of the current interval
     * and the number of events counted in it are packed into a single {@code long}, so that switching to a new interval
     * and resetting the counter happen in one CAS.
     * <p>
     * The low {@code countBits} bits hold the counter (just enough bits to represent {@code maxEvents}), the remaining
     * high bits hold the interval index, counted from {@code originNanos}. The interval index is therefore stored
     * modulo {@code 2^(64 - countBits)}; differences between interval indexes are evaluated in that width, so wrapping
     * is harmless unless the throttler stays idle for half that many intervals (for {@code maxEvents = 1000} and
     * 1ms intervals that's more than 200,000 years).
     */
    static final class CasThrottler extends Throttler {
        final long originNanos;
        final int countBits;
        final long countMask;
        final AtomicLong state;

        CasThrottler(long intervalNanos, long maxEvents, NanoClock nanoClock) {
            super(intervalNanos, maxEvents, nanoClock);
            this.originNanos = startOfPreviousInterval();
            this.countBits = 64 - Long.numberOfLeadingZeros(maxEvents);
            this.countMask = (1L << countBits) - 1;
            this.state = new AtomicLong(0); // interval 0, no events
        }

        @Override
        boolean throttle() {
            long t = nanoClock.nanoTime();
            while (true) {
                long s = state.get();
                long delta = intervalDelta(t, s);

                if (delta < 0) {
                    // `t` was read before another thread moved `state` to a later interval. Reading the clock again
                    // after reading `state` guarantees a `t` that is not behind `state` (nanoTime is monotonic), so
                    // a still negative delta can only come from the interval index wrapping around after a very long
                    // idle period, which we treat like any other move to a new interval.
                    t = nanoClock.nanoTime();
                    delta = intervalDelta(t, s);
                    if (delta < 0) {
                        delta = 1;
                    }
                }

                if (delta == 0) {
                    if ((s & countMask) >= maxEvents) {
                        return true;
                    }

                    if (state.compareAndSet(s, s + 1)) {
                        return false;
                    }
                } else if (state.compareAndSet(s, (intervalIndex(t) << countBits) | 1)) {
                    return false;
                }
            }
        }

        private long intervalIndex(long t) {
            return Math.floorDiv(t - originNanos, intervalNanos);
        }

        /**
         * The difference between the interval `t` falls into and the interval stored in `s`, sign-extended from the
         * width of the stored interval index.
         */
        private long intervalDelta(long t, long s) {
            long storedIndex = s >>> countBits;
            long startOfStoredInterval = originNanos + storedIndex * intervalNanos;
            long intoStoredInterval = t - startOfStoredInterval;
            if (intoStoredInterval >= 0 && intoStoredInterval < intervalNanos) {
                // Fast path, avoiding the division in `intervalIndex`
                return 0;
            }

            return ((intervalIndex(t) - storedIndex) << countBits) >> countBits;
        }
    }
}
