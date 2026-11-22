package com.github.mlangc.more.log4j2.filters;

import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.util.NanoClock;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class ThrottlingFilterBlackboxRaceExperiment {
    private static final Logger LOG = LogManager.getLogger(ThrottlingFilterBlackboxRaceExperiment.class);

    static class ZeroShiftedMemorizingNanoClock implements NanoClock {
        final long startNanos = System.nanoTime();
        final ThreadLocal<MutableLong> lastResult = ThreadLocal.withInitial(MutableLong::new);

        @Override
        public long nanoTime() {
            var res = nanoTimeNoMemo();
            lastResult.get().setValue(res);
            return res;
        }

        long nanoTimeNoMemo() {
            return System.nanoTime() - startNanos;
        }
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException, TimeoutException {
        LOG.printf(Level.INFO, "Warmup round:");
        runRaceExperiment(1, 100, 1, 50, LoadPattern.ALTERNATE, ThrottlingMode.SYNCHRONIZED, "  ");
        runRaceExperiment(1, 1000, 2, 200, LoadPattern.ALTERNATE, ThrottlingMode.SYNCHRONIZED, "  ");
        runRaceExperiment(1, 1000, 4, 200, LoadPattern.SATURATE, ThrottlingMode.BEST_EFFORT, "  ");

        LOG.printf(Level.INFO, "=============");

        var intervalMs = 1;
        var numIntervals = 1000;
        for (var numThreads : List.of(4, 100)) {
            for (var maxEvents : List.of(1000)) {
                LOG.printf(Level.INFO, "Using %3d threads and %6d events and %4d intervals of %4dms each:", numThreads, maxEvents, numIntervals, intervalMs);
                for (var loadPattern : List.of(LoadPattern.SATURATE, LoadPattern.ALTERNATE)) {
                    for (var throttlingMode : ThrottlingMode.values()) {
                        LOG.printf(Level.INFO, "    loadPattern=%s, throttlingMode=%s:", loadPattern, throttlingMode);
                        runRaceExperiment(intervalMs, maxEvents, numThreads, numIntervals, loadPattern, throttlingMode, "      ");
                    }
                }
            }
        }
    }

    enum LoadPattern {
        ALTERNATE, SATURATE;
    }

    enum ThrottlingMode {
        BEST_EFFORT, SYNCHRONIZED;

        ThrottlingFilter.Throttler newThrottler(long intervalNs, long maxEvents, NanoClock nanoClock) {
            return switch (this) {
                case BEST_EFFORT -> new ThrottlingFilter.BestEffortThrottler(intervalNs, maxEvents, nanoClock);
                case SYNCHRONIZED -> new ThrottlingFilter.SynchronizingThrottler(intervalNs, maxEvents, nanoClock);
            };
        }
    }

    static void runRaceExperiment(int intervalMs, long maxEvents, int numThreads, int numIntervals, LoadPattern loadPattern, ThrottlingMode throttlingMode, String prefix) throws InterruptedException, ExecutionException, TimeoutException {
        var executor = Executors.newFixedThreadPool(numThreads);
        try {
            var nanoClock = new ZeroShiftedMemorizingNanoClock();
            var intervalNs = TimeUnit.MILLISECONDS.toNanos(intervalMs);
            var throttler = throttlingMode.newThrottler(intervalNs, maxEvents, nanoClock);
            var warmupIntervals = 3;

            var stop = new AtomicBoolean();
            CompletableFuture.delayedExecutor((numIntervals + warmupIntervals + 1) * intervalNs, TimeUnit.NANOSECONDS)
                    .execute(() -> stop.set(true));

            Supplier<long[]> pushEventsToIntervals = () -> {
                var eventsPerInterval = new long[numIntervals + warmupIntervals];

                while (!stop.get()) {
                    if (loadPattern == LoadPattern.ALTERNATE) {
                        var t = nanoClock.nanoTimeNoMemo();
                        var intervalIdx = t / intervalNs;

                        if (intervalIdx % 2 != 0) {
                            var waitUntilNs = (intervalNs * (intervalIdx + 1) - 150);
                            while (nanoClock.nanoTimeNoMemo() < waitUntilNs) {
                                Thread.onSpinWait();
                            }
                        }
                    }

                    if (!throttler.throttle()) {
                        var intervalIdx = nanoClock.lastResult.get().longValue() / intervalNs;
                        if (intervalIdx >= eventsPerInterval.length) {
                            break;
                        }

                        eventsPerInterval[(int) intervalIdx]++;
                    }
                }

                return eventsPerInterval;
            };

            var jobs = Stream.generate(() -> CompletableFuture.supplyAsync(pushEventsToIntervals, executor))
                    .limit(numThreads)
                    .toList();

            CompletableFuture.allOf(jobs.toArray(new CompletableFuture<?>[0])).handle((r, e) -> {
                assertThat(e).isNull();

                var accumulatedEventsPerInterval = new long[numIntervals];
                for (var job : jobs) {
                    var eventsPerInterval = job.join();
                    for (int i = warmupIntervals; i < numIntervals + warmupIntervals; i++) {
                        accumulatedEventsPerInterval[i - warmupIntervals] += eventsPerInterval[i];
                    }
                }

                var numViolations = 0;
                var worstOverflow = 0L;
                var worstOverflowIdx = 0;
                var totalOverflow = 0L;
                var totalEvents = 0L;
                var eventsFromHotIntervals = 0L;
                var totalUnderflow = 0L;
                var underflowFromHotIntervals = 0L;

                for (int i = 0; i < numIntervals; i++) {
                    if (accumulatedEventsPerInterval[i] > maxEvents) {
                        numViolations++;
                        var overflow = accumulatedEventsPerInterval[i] - maxEvents;
                        if (overflow > worstOverflow) {
                            worstOverflow = overflow;
                            worstOverflowIdx = i;
                        }

                        totalOverflow += overflow;
                    } else if (accumulatedEventsPerInterval[i] < maxEvents) {
                        totalUnderflow += maxEvents - accumulatedEventsPerInterval[i];

                        if ((i + warmupIntervals) % 2 == 0) {
                            underflowFromHotIntervals += maxEvents - accumulatedEventsPerInterval[i];
                        }
                    }

                    totalEvents += accumulatedEventsPerInterval[i];

                    if ((i + warmupIntervals) % 2 == 0) {
                        eventsFromHotIntervals += accumulatedEventsPerInterval[i];
                    }
                }

                var effIntervals = loadPattern == LoadPattern.SATURATE ? numIntervals : numIntervals / 2;
                var effEvents = loadPattern == LoadPattern.SATURATE ? totalEvents : eventsFromHotIntervals;
                var surplusEvents = Math.max(0, effEvents - (effIntervals * maxEvents));
                var effUnderflow = loadPattern == LoadPattern.SATURATE ? totalUnderflow : underflowFromHotIntervals;

                LOG.printf(Level.INFO, "%stotalEvents  : %10d", prefix, totalEvents);
                LOG.printf(Level.INFO, "%snumViolations: %10d / %7.3f%%", prefix, numViolations, 100.0 * numViolations / effIntervals);
                LOG.printf(Level.INFO, "%sworstOverflow: %10d / %7.3f%% (@ idx %d)", prefix, worstOverflow, 100.0 * worstOverflow / maxEvents, worstOverflowIdx);
                LOG.printf(Level.INFO, "%stotalOverflow: %10d / %7.3f%%", prefix, totalOverflow, 100.0 * totalOverflow / (effIntervals * maxEvents));
                LOG.printf(Level.INFO, "%seffUnderflow : %10d / %7.3f%%", prefix, effUnderflow, 100.0 * effUnderflow / (effIntervals * maxEvents));
                LOG.printf(Level.INFO, "%ssurplusEvents: %10d / %7.3f%%", prefix, surplusEvents, 100.0 * surplusEvents / effEvents);

                return null;
            }).get(30, TimeUnit.SECONDS);

        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
