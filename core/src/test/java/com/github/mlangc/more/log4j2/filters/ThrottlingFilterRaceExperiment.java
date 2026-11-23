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

import org.apache.logging.log4j.core.util.NanoClock;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Manually run experiment (not part of the test suite) that measures how many events in excess of
 * {@code maxEvents} per interval {@link ThrottlingFilter} lets through because of the documented race between
 * {@code startTicks.compareAndSet(...)} and {@code eventCounter.set(1)}.
 *
 * <h2>Design</h2>
 * <ul>
 *     <li>The filter's intervals are aligned to multiples of {@code intervalNanos} on the clock it is given. The
 *     filter gets a clock that wraps {@link System#nanoTime()} and remembers the value {@code t} it handed out in the
 *     calling worker thread, so every accepted event can be bucketed by {@code floorDiv(t, intervalNanos)}.</li>
 *     <li>Harness skew: a thread whose {@code t} is already stale when it reaches the filter's state (another thread
 *     rolled the interval over in between) is counted by the filter against the newer interval, while the naive
 *     bucketing by {@code t} puts it into the older one. To exclude this, after each accepted event the worker
 *     reads the throttler's current interval start. If it is still in the interval of {@code t}, the
 *     filter must have counted the event in that interval ({@code startTicks} only moves forward), so the event is
 *     <em>definite</em>; otherwise it is <em>ambiguous</em>. Overshoot computed from definite events only is a
 *     lower bound of the true overshoot, with no skew. The raw overshoot (definite + ambiguous) is reported too.</li>
 *     <li>Candidate: {@link ThrottlingFilter.CasThrottler} (impl {@code cas}) packs interval and counter into a single
 *     {@code AtomicLong} and must, like the control, never report a definite overshoot.</li>
 *     <li>Control: {@link LockedReference} has exactly the same semantics, including reading the clock before
 *     synchronizing, but updates its state atomically. The same harness must never report a definite overshoot for
 *     it; its raw overshoot shows how large the harness skew is.</li>
 *     <li>Two workloads:
 *     <ul>
 *         <li>{@code SATURATE}: all threads call the filter in a tight loop. Every interval is saturated, so the
 *         stale counter seen by the racing threads is already {@code >= maxEvents} and the race is expected to be
 *         mostly harmless, except for the very first rollover (the counter starts at 0), which is excluded from the
 *         statistics and reported separately as {@code w0Over}.</li>
 *         <li>{@code BURST}: alternating quiet and burst intervals. In each quiet interval a single thread logs exactly
 *         one event (so the counter ends at 1); right at the start of the following interval all threads burst in a
 *         tight loop. This is the worst case for the race (stale counter = 1, maximal contention at the rollover),
 *         and models a service that logs rarely until an error storm hits many threads at once. Only the burst
 *         intervals are used for the statistics.</li>
 *     </ul>
 *     </li>
 * </ul>
 *
 * <h2>Running</h2>
 * <pre>
 * ./mvnw -q test-compile -pl core -am
 * ./mvnw -q -pl core dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
 * java -cp core/target/test-classes:core/target/classes:$(cat core/target/cp.txt) \
 *     com.github.mlangc.more.log4j2.filters.ThrottlingFilterRaceExperiment
 * </pre>
 * System properties (all optional): {@code intervals} (e.g. {@code 100us,1ms,10ms,100ms,1s}), {@code maxEvents}
 * (e.g. {@code 1,10,1000}), {@code threads} (e.g. {@code 1,2,4}; default 1,2,4,8,cores,4*cores), {@code modes}
 * ({@code SATURATE,BURST}), {@code impls} ({@code throttling,cas,synchronized,reference}; default {@code throttling,cas,reference}), {@code windowsScale} (multiplies the
 * default number of measured windows per interval), {@code out} (CSV output file).
 */
public final class ThrottlingFilterRaceExperiment {
    private ThrottlingFilterRaceExperiment() {
    }

    enum Mode {SATURATE, BURST}

    interface Gate {
        boolean pass();

        long currentStartTicks();
    }

    static final class Worker extends Thread {
        long lastT;

        Worker(Runnable r, String name) {
            super(r, name);
            setDaemon(true);
        }
    }

    static final NanoClock CLOCK = () -> {
        long t = System.nanoTime();
        if (Thread.currentThread() instanceof Worker w) {
            w.lastT = t;
        }
        return t;
    };

    /**
     * {@link ThrottlingFilter.BestEffortThrottler}, the implementation {@link ThrottlingFilter} uses.
     */
    static final class ThrottlingGate implements Gate {
        final ThrottlingFilter.BestEffortThrottler throttler;

        ThrottlingGate(long intervalNanos, long maxEvents) {
            throttler = new ThrottlingFilter.BestEffortThrottler(intervalNanos, maxEvents, CLOCK);
        }

        @Override
        public boolean pass() {
            return !throttler.throttle();
        }

        @Override
        public long currentStartTicks() {
            return throttler.startTicks.get();
        }
    }

    /**
     * {@link ThrottlingFilter.CasThrottler}: interval index and counter packed into a single {@code AtomicLong}.
     */
    static final class CasGate implements Gate {
        final ThrottlingFilter.CasThrottler throttler;

        CasGate(long intervalNanos, long maxEvents) {
            throttler = new ThrottlingFilter.CasThrottler(intervalNanos, maxEvents, CLOCK);
        }

        @Override
        public boolean pass() {
            return !throttler.throttle();
        }

        @Override
        public long currentStartTicks() {
            long intervalIndex = throttler.state.get() >>> throttler.countBits;
            return throttler.originNanos + intervalIndex * throttler.intervalNanos;
        }
    }

    /**
     * {@link ThrottlingFilter.SynchronizingThrottler}.
     */
    static final class SynchronizedGate implements Gate {
        final ThrottlingFilter.SynchronizingThrottler throttler;

        SynchronizedGate(long intervalNanos, long maxEvents) {
            throttler = new ThrottlingFilter.SynchronizingThrottler(intervalNanos, maxEvents, CLOCK);
        }

        @Override
        public boolean pass() {
            return !throttler.throttle();
        }

        @Override
        public long currentStartTicks() {
            synchronized (throttler) {
                return throttler.startNanos;
            }
        }
    }

    static Gate newGate(String impl, long intervalNanos, long maxEvents) {
        return switch (impl) {
            case "throttling" -> new ThrottlingGate(intervalNanos, maxEvents);
            case "cas" -> new CasGate(intervalNanos, maxEvents);
            case "synchronized" -> new SynchronizedGate(intervalNanos, maxEvents);
            case "reference" -> new LockedReference(intervalNanos, maxEvents);
            default -> throw new IllegalArgumentException("Unknown impl: " + impl);
        };
    }

    /**
     * Same semantics as {@link ThrottlingFilter}, including reading the clock once before touching the state, but the
     * state is updated atomically.
     */
    static final class LockedReference implements Gate {
        final long intervalNanos;
        final long maxEvents;
        volatile long startTicks;
        long eventCounter;

        LockedReference(long intervalNanos, long maxEvents) {
            this.intervalNanos = intervalNanos;
            this.maxEvents = maxEvents;
            this.startTicks = Math.floorDiv(CLOCK.nanoTime(), intervalNanos) * intervalNanos;
        }

        @Override
        public boolean pass() {
            long t = CLOCK.nanoTime();
            synchronized (this) {
                long t0 = startTicks;
                if (t - t0 >= intervalNanos) {
                    startTicks = t - (t - t0) % intervalNanos;
                    eventCounter = 1;
                    return true;
                }

                if (eventCounter >= maxEvents) {
                    return false;
                }

                eventCounter++;
                return true;
            }
        }

        @Override
        public long currentStartTicks() {
            return startTicks;
        }
    }

    record Stats(String impl, Mode mode, long intervalNanos, long maxEvents, int threads, int windows,
                 double meanPassed, double rawMeanOver, long rawMaxOver,
                 double meanOver, double fracOver, long p99, long p999, long maxOver,
                 long w0Over, double meanAmbiguous, double meanQuiet, long outOfRange, String histogram) {
    }

    static void spinUntil(long target) {
        while (System.nanoTime() < target) {
            Thread.onSpinWait();
        }
    }

    static final class Recorder {
        final long[] definite;
        final long[] ambiguous;
        long outOfRange;

        Recorder(int windows) {
            definite = new long[windows];
            ambiguous = new long[windows];
        }

        void record(Gate gate, long t, long intervalNanos, long startWindow) {
            long wt = Math.floorDiv(t, intervalNanos);
            long ws = Math.floorDiv(gate.currentStartTicks(), intervalNanos);
            long idx = wt - startWindow;
            if (idx >= 0 && idx < definite.length) {
                if (ws == wt) {
                    definite[(int) idx]++;
                } else {
                    ambiguous[(int) idx]++;
                }
            } else {
                outOfRange++;
            }
        }
    }

    static Stats run(String impl, Mode mode, long intervalNanos, long maxEvents, int threads, int windows) throws InterruptedException {
        Gate gate = newGate(impl, intervalNanos, maxEvents);

        long now = System.nanoTime();
        // Leave enough time to start all threads before the first measured window.
        long startWindow = Math.floorDiv(now + TimeUnit.MILLISECONDS.toNanos(100), intervalNanos) + 1;
        int totalWindows = mode == Mode.BURST ? 2 * windows : windows;
        Recorder[] recorders = new Recorder[threads];
        long burstMargin = Math.max(1, intervalNanos / 20);
        CountDownLatch done = new CountDownLatch(threads);

        List<Worker> workers = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            int id = i;
            Recorder rec = recorders[i] = new Recorder(totalWindows);
            Worker[] self = new Worker[1];
            Runnable body = () -> {
                Worker w = self[0];
                try {
                    if (mode == Mode.SATURATE) {
                        long end = (startWindow + totalWindows) * intervalNanos;
                        spinUntil(startWindow * intervalNanos);
                        while (System.nanoTime() < end) {
                            if (gate.pass()) {
                                rec.record(gate, w.lastT, intervalNanos, startWindow);
                            }
                        }
                    } else {
                        for (int r = 0; r < windows; r++) {
                            long quietStart = (startWindow + 2L * r) * intervalNanos;
                            long burstStart = quietStart + intervalNanos;
                            long burstEnd = burstStart + intervalNanos - burstMargin;
                            if (id == 0) {
                                spinUntil(quietStart);
                                if (gate.pass()) {
                                    rec.record(gate, w.lastT, intervalNanos, startWindow);
                                }
                            }
                            spinUntil(burstStart);
                            while (System.nanoTime() < burstEnd) {
                                if (gate.pass()) {
                                    rec.record(gate, w.lastT, intervalNanos, startWindow);
                                }
                            }
                        }
                    }
                } finally {
                    done.countDown();
                }
            };
            Worker w = new Worker(body, "race-" + i);
            self[0] = w;
            workers.add(w);
        }

        workers.forEach(Thread::start);
        done.await();

        long[] definite = new long[totalWindows];
        long[] ambiguous = new long[totalWindows];
        long oor = 0;
        for (Recorder r : recorders) {
            for (int i = 0; i < totalWindows; i++) {
                definite[i] += r.definite[i];
                ambiguous[i] += r.ambiguous[i];
            }
            oor += r.outOfRange;
        }

        long w0Over = mode == Mode.SATURATE ? Math.max(0, definite[0] - maxEvents) : -1;

        // SATURATE: skip window 0 (first rollover with a counter of 0, reported as w0Over).
        // BURST: use the burst windows only.
        List<Integer> measured = new ArrayList<>();
        double quietSum = 0;
        int quietN = 0;
        for (int i = 0; i < totalWindows; i++) {
            if (mode == Mode.SATURATE) {
                if (i > 0) measured.add(i);
            } else if (i % 2 == 1) {
                measured.add(i);
            } else {
                quietSum += definite[i] + ambiguous[i];
                quietN++;
            }
        }

        int n = measured.size();
        long[] over = new long[n];
        double passedSum = 0, rawOverSum = 0, ambSum = 0;
        long rawMax = 0;
        int nOver = 0;
        for (int j = 0; j < n; j++) {
            int i = measured.get(j);
            long raw = definite[i] + ambiguous[i];
            passedSum += raw;
            ambSum += ambiguous[i];
            long rawOver = Math.max(0, raw - maxEvents);
            rawOverSum += rawOver;
            rawMax = Math.max(rawMax, rawOver);
            over[j] = Math.max(0, definite[i] - maxEvents);
            if (over[j] > 0) nOver++;
        }
        long[] sorted = over.clone();
        Arrays.sort(sorted);

        return new Stats(impl, mode, intervalNanos, maxEvents, threads, n,
                passedSum / n, rawOverSum / n, rawMax,
                Arrays.stream(over).average().orElse(0), (double) nOver / n,
                pct(sorted, 0.99), pct(sorted, 0.999), n == 0 ? 0 : sorted[n - 1],
                w0Over, ambSum / n, quietN == 0 ? Double.NaN : quietSum / quietN, oor, histogram(over));
    }

    static long pct(long[] sorted, double q) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    /**
     * Power-of-two histogram of the overshoot, omitting the 0 bucket: "1:3 2-3:1" means 3 windows with overshoot 1
     * and 1 window with overshoot 2 or 3.
     */
    static String histogram(long[] over) {
        long[] buckets = new long[64];
        for (long o : over) {
            if (o > 0) buckets[63 - Long.numberOfLeadingZeros(o)]++;
        }
        StringBuilder sb = new StringBuilder();
        for (int b = 0; b < 64; b++) {
            if (buckets[b] == 0) continue;
            long lo = 1L << b;
            long hi = (1L << (b + 1)) - 1;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(lo == hi ? Long.toString(lo) : lo + "-" + hi).append(':').append(buckets[b]);
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    static long parseDuration(String s) {
        s = s.trim();
        if (s.endsWith("us")) return TimeUnit.MICROSECONDS.toNanos(Long.parseLong(s.substring(0, s.length() - 2)));
        if (s.endsWith("ms")) return TimeUnit.MILLISECONDS.toNanos(Long.parseLong(s.substring(0, s.length() - 2)));
        if (s.endsWith("s")) return TimeUnit.SECONDS.toNanos(Long.parseLong(s.substring(0, s.length() - 1)));
        throw new IllegalArgumentException("Cannot parse duration: " + s);
    }

    static String formatDuration(long nanos) {
        if (nanos % 1_000_000_000L == 0) return nanos / 1_000_000_000L + "s";
        if (nanos % 1_000_000L == 0) return nanos / 1_000_000L + "ms";
        return nanos / 1_000L + "us";
    }

    /**
     * Default number of measured windows (rounds for BURST) per interval length, keeping each config at a few seconds.
     */
    static int defaultWindows(long intervalNanos) {
        long ms = TimeUnit.NANOSECONDS.toMillis(intervalNanos);
        if (ms < 1) return 5000;
        if (ms < 10) return 1000;
        if (ms < 100) return 200;
        if (ms < 1000) return 20;
        return 4;
    }

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        List<Long> intervals = Arrays.stream(System.getProperty("intervals", "1ms,10ms,100ms,1s").split(","))
                .map(ThrottlingFilterRaceExperiment::parseDuration).toList();
        List<Long> maxEventsList = Arrays.stream(System.getProperty("maxEvents", "1,10,1000").split(","))
                .map(String::trim).map(Long::parseLong).toList();
        String threadsProp = System.getProperty("threads");
        List<Integer> threadsList = threadsProp != null
                ? Arrays.stream(threadsProp.split(",")).map(String::trim).map(Integer::parseInt).toList()
                : new ArrayList<>(new TreeSet<>(List.of(1, 2, 4, 8, cores, 4 * cores)));
        List<Mode> modes = Arrays.stream(System.getProperty("modes", "SATURATE,BURST").split(","))
                .map(String::trim).map(Mode::valueOf).toList();
        List<String> impls = Arrays.stream(System.getProperty("impls", "throttling,cas,reference").split(","))
                .map(String::trim).toList();
        double windowsScale = Double.parseDouble(System.getProperty("windowsScale", "1"));
        String out = System.getProperty("out");

        System.out.printf("# java.version=%s java.vm=%s os=%s/%s availableProcessors=%d%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                System.getProperty("os.name"), System.getProperty("os.arch"), cores);
        System.out.println("# raw* = bucketed by t only (includes harness skew); meanOver..maxOv/w0Over/hist = definite events only (lower bound, no skew)");

        // Warm up all implementations (JIT) before measuring.
        for (String impl : impls) {
            run(impl, Mode.SATURATE, TimeUnit.MILLISECONDS.toNanos(1), 10, Math.min(4, cores), 500);
        }

        System.out.println(String.format(Locale.ROOT, "%-10s %-8s %5s %5s %4s %5s %9s %8s %6s | %8s %7s %4s %5s %6s %6s %6s %6s %4s %s",
                "impl", "mode", "intvl", "max", "thr", "wins", "meanPass", "rawMeanO", "rawMax",
                "meanOver", "fracOv", "p99", "p999", "maxOv", "w0Over", "ambig", "quiet", "oor", "overshootHistogram"));

        try (PrintWriter csv = out == null ? null : new PrintWriter(Files.newBufferedWriter(Path.of(out)))) {
            if (csv != null) {
                csv.println("impl,mode,interval,maxEvents,threads,windows,meanPassed,rawMeanOver,rawMaxOver,meanOver,fracOver,p99,p999,maxOver,w0Over,meanAmbiguous,meanQuiet,outOfRange,histogram,cores,java");
            }

            for (long interval : intervals) {
                int windows = Math.max(2, (int) Math.round(defaultWindows(interval) * windowsScale));
                for (long maxEvents : maxEventsList) {
                    for (int threads : threadsList) {
                        for (Mode mode : modes) {
                            for (String impl : impls) {
                                Stats s = run(impl, mode, interval, maxEvents, threads, windows);
                                System.out.printf(Locale.ROOT, "%-10s %-8s %5s %5d %4d %5d %9.1f %8.3f %6d | %8.4f %7.4f %4d %5d %6d %6s %6.2f %6s %4d %s%n",
                                        s.impl, s.mode, formatDuration(s.intervalNanos), s.maxEvents, s.threads, s.windows,
                                        s.meanPassed, s.rawMeanOver, s.rawMaxOver,
                                        s.meanOver, s.fracOver, s.p99, s.p999, s.maxOver,
                                        s.w0Over < 0 ? "-" : Long.toString(s.w0Over), s.meanAmbiguous,
                                        Double.isNaN(s.meanQuiet) ? "-" : String.format(Locale.ROOT, "%.2f", s.meanQuiet),
                                        s.outOfRange, s.histogram);
                                if (csv != null) {
                                    csv.printf(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%.3f,%.5f,%d,%.5f,%.6f,%d,%d,%d,%d,%.4f,%.3f,%d,%s,%d,%s%n",
                                            s.impl, s.mode, formatDuration(s.intervalNanos), s.maxEvents, s.threads, s.windows,
                                            s.meanPassed, s.rawMeanOver, s.rawMaxOver, s.meanOver, s.fracOver, s.p99, s.p999,
                                            s.maxOver, s.w0Over, s.meanAmbiguous, s.meanQuiet, s.outOfRange, s.histogram,
                                            cores, System.getProperty("java.version"));
                                    csv.flush();
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
