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
package com.github.mlangc.more.log4j2.benchmarks;

import com.github.mlangc.more.log4j2.appenders.AsyncHttpAppender;
import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.exception.UncheckedInterruptedException;
import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.lang.System.out;


public class AsyncHttpAppenderNonJmhBenchmarks {
    private static final ScheduledExecutorService SCHEDULED_EXECUTOR_SERVICE = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setDaemon(true).setNameFormat(AsyncHttpAppenderNonJmhBenchmarks.class.getSimpleName() + ":%d").build());

    public static void main(String[] args) throws Exception {
        System.setProperty("benchmarkLog4jPattern", "%d{HH:mm:ss.SSS} %-5level %c{2}@[%t] - %msg%n");

        new BenchmarkTemplate() {
            @Override
            Cfg loadConfig() {
                return new Cfg(50_000, 50_000, 30, 3);
            }

            @Override
            String log4jConfigLocation() {
                return "AsyncHttpAppenderNonJmhBenchmarks.dynatraceOptimized.xml";
            }

            @Override
            int parallelism() {
                return 4;
            }
        }.run();
    }

    public static class BatchCompletionListener implements AsyncHttpAppender.BatchCompletionListener {
        private static final Logger LOG = LogManager.getLogger(BatchCompletionListener.class);

        volatile AtomicBoolean stopFlag;
        final AtomicLong lastDroppedNanos = new AtomicLong(Long.MIN_VALUE);

        final AtomicLong completedBytes = new AtomicLong();
        final AtomicLong completedBytesUncompressed = new AtomicLong();
        final AtomicLong completedLogEvents = new AtomicLong();

        final AtomicLong droppedBatches = new AtomicLong();

        final ScheduledFuture<?> processAndResetStatsSchedule;
        final ScheduledFuture<?> droppedBatchReportingSchedule;

        public BatchCompletionListener() {
            processAndResetStatsSchedule = SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(this::processAndResetStatistics, 1, 1, TimeUnit.SECONDS);
            droppedBatchReportingSchedule = SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(this::reportDroppedBatches, 1, 1, TimeUnit.SECONDS);
        }

        void reportDroppedBatches() {
            var dropped = droppedBatches.getAndSet(0);
            if (dropped > 0) {
                out.printf("Dropped %s batches in the last second%n", dropped);
            }
        }

        void processAndResetStatistics() {
            var localStopFlag = stopFlag;
            if (localStopFlag.get() && processAndResetStatsSchedule != null) {
                processAndResetStatsSchedule.cancel(false);
                droppedBatchReportingSchedule.cancel(false);
                return;
            }

            LOG.info("completedBytes={}, completedBytesUncompressed={}, completedLogEvents={}",
                    completedBytes.get(), completedBytesUncompressed.get(), completedLogEvents.get());

            completedBytes.set(0);
            completedBytesUncompressed.set(0);
            completedLogEvents.set(0);
        }

        @Override
        public void onBatchCompletionEvent(AsyncHttpAppender.BatchCompletionEvent event) {
            AsyncHttpAppender.logBatchCompletionEvent(LOG, ForkJoinPool.commonPool(), event);

            if (event.type() instanceof AsyncHttpAppender.BatchDropped) {
                var currentNanos = System.nanoTime();
                lastDroppedNanos.updateAndGet(v -> Math.max(v, currentNanos));
                droppedBatches.incrementAndGet();
            } else if (event.type() instanceof AsyncHttpAppender.BatchDeliveredSuccess) {
                completedBytes.addAndGet(event.context().bytesEffective());
                completedLogEvents.addAndGet(event.context().logEvents());
                completedBytesUncompressed.addAndGet(event.context().bytesUncompressed());
            }
        }
    }

    static abstract class BenchmarkTemplate {
        record Cfg(int logsPerSecond0, int logsPerSecondIncrement0, int waitForDroppedBatchesSecs, int iterations) { }

        Cfg loadConfig() {
            return new Cfg(5000, 1000, 30, 3);
        }

        abstract String log4jConfigLocation();

        abstract int parallelism();

        void setupBackend() throws Exception {

        }

        void shutdown() throws Exception {

        }

        void logInfoMessage(Logger log, long threadLocalSequence) {
            log.info("[{}] A prefix for king & country: {}", threadLocalSequence, RandomStringUtils.insecure().nextAlphanumeric(10, 30));
        }

        void run() throws Exception {
            setupBackend();

            var cfg = loadConfig();
            var logLines = new LongAdder();
            var lastLogLines = new MutableLong(0);
            var lastRecordedLogsPerSecondRate = new MutableLong(-1);

            ScheduledFuture<?> benchmarkDriverSchedule = null;
            try (var context = TestHelpers.loggerContextFromTestResource(log4jConfigLocation())) {
                var batchCompletionListener = context.getConfiguration().getAppenders().values().stream()
                        .flatMap(a -> {
                            if (a instanceof AsyncHttpAppender asyncHttpAppender) {
                                return Stream.of((BatchCompletionListener) asyncHttpAppender.batchCompletionListener());
                            } else {
                                return Stream.empty();
                            }
                        }).findFirst().orElseThrow();


                var driverState = new Object() {
                    int iteration;
                    boolean rampingUp = true;
                    int targetLogsPerSecond = cfg.logsPerSecond0;
                    int currentIncrement = cfg.logsPerSecondIncrement0;
                    final Semaphore permits = new Semaphore(cfg.logsPerSecond0);
                    final AtomicBoolean shuttingDown = new AtomicBoolean();
                };

                batchCompletionListener.stopFlag = driverState.shuttingDown;

                Runnable runBenchmarkDriver = () -> {
                    if (driverState.shuttingDown.get()) {
                        return;
                    }

                    var lastDroppedNanos = batchCompletionListener.lastDroppedNanos.get();
                    var currentNanos = System.nanoTime();

                    int delta;
                    if (lastDroppedNanos != Long.MIN_VALUE && driverState.rampingUp) {
                        driverState.rampingUp = false;
                        delta = -1;
                        out.printf("Starting to ramp down at iteration %s%n", driverState.iteration);
                    } else if (!driverState.rampingUp && lastDroppedNanos + TimeUnit.SECONDS.toNanos(cfg.waitForDroppedBatchesSecs()) <= currentNanos) {
                        if (driverState.iteration < cfg.iterations && driverState.currentIncrement > 1) {
                            out.printf("Proceeding with iteration %s, since no logs have been dropped for at least %s seconds", driverState.iteration + 1, cfg.waitForDroppedBatchesSecs);
                            driverState.iteration++;
                            driverState.currentIncrement /= 2;
                            driverState.rampingUp = true;
                            batchCompletionListener.lastDroppedNanos.set(Long.MIN_VALUE);
                            delta = 1;
                        } else {
                            out.printf("Stopping the benchmark at iteration %s since no logs have been dropped for at least %s seconds", driverState.iteration, cfg.waitForDroppedBatchesSecs);
                            driverState.shuttingDown.set(true);
                            delta = 0;
                        }
                    } else if (!driverState.rampingUp && lastDroppedNanos + TimeUnit.SECONDS.toNanos(1) <= currentNanos) {
                        delta = 0;
                    } else if (!driverState.rampingUp) {
                        delta = -1;
                    } else {
                        delta = 1;
                    }

                    var currentLogLines = logLines.longValue();
                    var newLogLines = currentLogLines - lastLogLines.longValue();
                    lastRecordedLogsPerSecondRate.setValue(newLogLines);
                    lastLogLines.setValue(currentLogLines);

                    var newPerSecondTarget = driverState.targetLogsPerSecond + delta * driverState.currentIncrement;
                    var lastPerSecondTarget = driverState.targetLogsPerSecond;
                    out.printf("Trying %s logs per second; last achieved rate at %s with target %s%n", newPerSecondTarget, newLogLines, lastPerSecondTarget);

                    if (newLogLines * 1.1 < lastPerSecondTarget && driverState.rampingUp) {
                        out.printf("Keeping the current target rate, since even the previous one could not be sustained%n");
                        newPerSecondTarget = lastPerSecondTarget;
                    }

                    if (newPerSecondTarget <= 0) {
                        if (lastPerSecondTarget > 1) {
                            out.printf("New per second target would be at zero or below dividing last target rate of %s by 2%n", lastPerSecondTarget);
                            newPerSecondTarget = lastPerSecondTarget / 2;
                            driverState.currentIncrement = newPerSecondTarget;
                        } else {
                            out.printf("Shutting down the benchmark, since the new target request rate would be 0%n");
                            driverState.shuttingDown.set(true);
                            return;
                        }
                    }

                    driverState.targetLogsPerSecond = newPerSecondTarget;
                    driverState.permits.release(newPerSecondTarget);
                };


                out.printf("Loaded context with config %s%n", context.getConfiguration());

                var log = context.getLogger(getClass());

                Runnable loggerLoop = () -> {
                    try {
                        out.printf("Started loggerLoop[%s]%n", Thread.currentThread().getName());

                        var sequence = 0L;
                        while (!driverState.shuttingDown.get()) {
                            if (driverState.permits.tryAcquire(1, TimeUnit.MILLISECONDS)) {
                                logInfoMessage(log, sequence++);
                                logLines.increment();
                            }
                        }

                        log.info("Stopping...");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new UncheckedInterruptedException(e);
                    }
                };

                benchmarkDriverSchedule = SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(runBenchmarkDriver, 1, 1, TimeUnit.SECONDS);
                IntStream.range(0, parallelism())
                        .mapToObj(ignore -> CompletableFuture.runAsync(loggerLoop))
                        .toList()
                        .forEach(CompletableFuture::join);
            } finally {
                if (benchmarkDriverSchedule != null) {
                    benchmarkDriverSchedule.cancel(false);
                }

                shutdown();
            }

            out.printf("[%s] - %s%n", getClass().getSimpleName(), "#".repeat(80));
            out.printf("[%s] - achieved %s logs per second without dropping%n", getClass().getSimpleName(), lastRecordedLogsPerSecondRate.longValue());
            out.printf("[%s] - %s%n", getClass().getSimpleName(), "#".repeat(80));
        }
    }

    static abstract class WiremockBenchmarkTemplate extends BenchmarkTemplate {
        final WireMockServer wireMockServer = new WireMockServer(options().dynamicPort().maxRequestJournalEntries(100));
        final String wireMockPath = "/logs/" + getClass().getSimpleName();

        @Override
        String log4jConfigLocation() {
            return "AsyncHttpAppenderNonJmhBenchmarks.wireMockVanilla.xml";
        }

        @Override
        void setupBackend() {
            wireMockServer.start();
            var wireMockHttpUrl = "http://localhost:" + wireMockServer.port() + wireMockPath;
            System.setProperty("wireMockHttpUrl", wireMockHttpUrl);

            var stubConfiguration = post(urlEqualTo(wireMockPath));
            configureWireMock(stubConfiguration);
            wireMockServer.stubFor(stubConfiguration);
        }

        void configureWireMock(MappingBuilder mappingBuilder) {
            mappingBuilder.willReturn(ok());
        }

        @Override
        void shutdown() {
            wireMockServer.verify(moreThan(10), postRequestedFor(urlEqualTo(wireMockPath)));
            wireMockServer.stop();
        }
    }
}
