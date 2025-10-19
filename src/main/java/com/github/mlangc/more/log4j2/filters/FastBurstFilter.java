package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Plugin(name = "FastBurstFilter", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE, printObject = true)
public final class FastBurstFilter extends AbstractFilter {

    private static final long NANOS_IN_SECONDS = 1000000000;

    private static final int DEFAULT_RATE = 10;

    private static final int DEFAULT_RATE_MULTIPLE = 100;

    /**
     * Level of messages to be filtered. Anything at or below this level will be
     * filtered out if <code>maxBurst</code> has been exceeded. The default is
     * WARN meaning any messages that are higher than warn will be logged
     * regardless of the size of a burst.
     */
    private final Level level;

    private final long burstInterval;

    private Future<?> schedule;

    private final AtomicLong logPermits;

    private final long maxBurst;

    FastBurstFilter(
            final Level level, final float rate, final long maxBurst, final Result onMatch, final Result onMismatch) {
        super(onMatch, onMismatch);
        this.level = level;
        this.logPermits = new AtomicLong(maxBurst);
        this.maxBurst = maxBurst;
        this.burstInterval = (long) (NANOS_IN_SECONDS * (maxBurst / rate));
        this.schedule = createSchedule();
    }

    private Future<?> createSchedule() {
        return ForkJoinPool.commonPool().scheduleAtFixedRate(this::refillPermits, burstInterval, burstInterval, TimeUnit.NANOSECONDS);
    }

    private void refillPermits() {
        logPermits.set(maxBurst);
    }

    @Override
    public Result filter(
            final Logger logger, final Level level, final Marker marker, final String msg, final Object... params) {
        return filter(level);
    }

    @Override
    public Result filter(
            final Logger logger, final Level level, final Marker marker, final Object msg, final Throwable t) {
        return filter(level);
    }

    @Override
    public Result filter(
            final Logger logger, final Level level, final Marker marker, final Message msg, final Throwable t) {
        return filter(level);
    }

    @Override
    public Result filter(final LogEvent event) {
        return filter(event.getLevel());
    }

    @Override
    public Result filter(
            final Logger logger, final Level level, final Marker marker, final String msg, final Object p0) {
        return filter(level);
    }

    @Override
    public Result filter(
            final Logger logger,
            final Level level,
            final Marker marker,
            final String msg,
            final Object p0,
            final Object p1) {
        return filter(level);
    }

    @Override
    public Result filter(
            final Logger logger,
            final Level level,
            final Marker marker,
            final String msg,
            final Object p0,
            final Object p1,
            final Object p2) {
        return filter(level);
    }

    @Override
    public Result filter(
            final Logger logger,
            final Level level,
            final Marker marker,
            final String msg,
            final Object p0,
            final Object p1,
            final Object p2,
            final Object p3) {
        return filter(level);
    }

    @Override
    public Result filter(
            final Logger logger,
            final Level level,
            final Marker marker,
            final String msg,
            final Object p0,
            final Object p1,
            final Object p2,
            final Object p3,
            final Object p4) {
        return filter(level);
    }

    @Override
    public Result filter(
            final Logger logger,
            final Level level,
            final Marker marker,
            final String msg,
            final Object p0,
            final Object p1,
            final Object p2,
            final Object p3,
            final Object p4,
            final Object p5) {
        return filter(level);
    }

    @Override
    public Result filter(
            final Logger logger,
            final Level level,
            final Marker marker,
            final String msg,
            final Object p0,
            final Object p1,
            final Object p2,
            final Object p3,
            final Object p4,
            final Object p5,
            final Object p6) {
        return filter(level);
    }

    @Override
    public Result filter(
            final Logger logger,
            final Level level,
            final Marker marker,
            final String msg,
            final Object p0,
            final Object p1,
            final Object p2,
            final Object p3,
            final Object p4,
            final Object p5,
            final Object p6,
            final Object p7) {
        return filter(level);
    }

    @Override
    public Result filter(
            final Logger logger,
            final Level level,
            final Marker marker,
            final String msg,
            final Object p0,
            final Object p1,
            final Object p2,
            final Object p3,
            final Object p4,
            final Object p5,
            final Object p6,
            final Object p7,
            final Object p8) {
        return filter(level);
    }

    @Override
    public Result filter(
            final Logger logger,
            final Level level,
            final Marker marker,
            final String msg,
            final Object p0,
            final Object p1,
            final Object p2,
            final Object p3,
            final Object p4,
            final Object p5,
            final Object p6,
            final Object p7,
            final Object p8,
            final Object p9) {
        return filter(level);
    }

    private boolean acquireLogPermit() {
        return logPermits.get() > 0 && logPermits.decrementAndGet() >= 0;
    }

    /**
     * Decide if we're going to log <code>event</code> based on whether the
     * maximum burst of log statements has been exceeded.
     *
     * @param level The log level.
     * @return The onMatch value if the filter passes, onMismatch otherwise.
     */
    private Result filter(final Level level) {
        if (this.level.isMoreSpecificThan(level)) {
            return acquireLogPermit() ? onMatch : onMismatch;
        }
        return onMatch;
    }

    @Override
    public String toString() {
        return "level=" + level.toString() + ", interval=" + burstInterval + ", max=" + maxBurst;
    }

    /**
     * For testing & compatibility with the original burst filter.
     */
    public long getAvailable() {
        return logPermits.get();
    }

    /**
     * For testing & compatibility with the original burst filter.
     */
    public void clear() {
        schedule.cancel(false);
        schedule = createSchedule();
        refillPermits();
    }

    @PluginBuilderFactory
    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder extends AbstractFilterBuilder<Builder>
            implements org.apache.logging.log4j.core.util.Builder<FastBurstFilter> {

        @PluginBuilderAttribute
        private Level level = Level.WARN;

        @PluginBuilderAttribute
        private float rate = DEFAULT_RATE;

        @PluginBuilderAttribute
        private long maxBurst;

        /**
         * Sets the logging level to use.
         * @param level the logging level to use.
         * @return this
         */
        public Builder setLevel(final Level level) {
            this.level = level;
            return this;
        }

        /**
         * Sets the average number of events per second to allow.
         * @param rate the average number of events per second to allow. This must be a positive number.
         * @return this
         */
        public Builder setRate(final float rate) {
            this.rate = rate;
            return this;
        }

        /**
         * Sets the maximum number of events that can occur before events are filtered for exceeding the average rate.
         * @param maxBurst Sets the maximum number of events that can occur before events are filtered for exceeding the average rate.
         * The default is 10 times the rate.
         * @return this
         */
        public Builder setMaxBurst(final long maxBurst) {
            this.maxBurst = maxBurst;
            return this;
        }

        @Override
        public FastBurstFilter build() {
            if (this.rate <= 0) {
                this.rate = DEFAULT_RATE;
            }
            if (this.maxBurst <= 0) {
                this.maxBurst = (long) (this.rate * DEFAULT_RATE_MULTIPLE);
            }
            return new FastBurstFilter(this.level, this.rate, this.maxBurst, this.getOnMatch(), this.getOnMismatch());
        }
    }

    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        this.schedule.cancel(false);
        return super.stop(timeout, timeUnit);
    }
}
