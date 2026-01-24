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
package com.github.mlangc.more.log4j2.benchmarks;

import com.github.mlangc.more.log4j2.appenders.AsyncHttpAppender;
import com.github.mlangc.more.log4j2.test.helpers.CountingAppender;
import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.status.StatusLogger;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static com.github.mlangc.more.log4j2.test.helpers.WireMockHelpers.configureMappingWithRandomFailuresAndTimeouts;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.lang.System.out;


public class AsyncHttpAppenderNonJmhBenchmarks {
    static final Marker BENCHMARK_SFM_MARKER = MarkerManager.getMarker("BenchmarkSfm");

    private static final ScheduledExecutorService SCHEDULED_EXECUTOR_SERVICE = Executors.newScheduledThreadPool(4,
            new ThreadFactoryBuilder().setDaemon(true).setNameFormat(AsyncHttpAppenderNonJmhBenchmarks.class.getSimpleName() + ":%d").build());

    public static void main(String[] args) throws Exception {
        System.setProperty("benchmarkLog4jPattern", "%d{HH:mm:ss.SSS} %-5level %c{2}@[%t] - %msg%n");

        new BenchmarkTemplate() {
            @Override
            String log4jConfigLocation() {
                return "AsyncHttpAppenderNonJmhBenchmarks.dynatraceOptimized.xml";
            }

            @Override
            int parallelism() {
                return 4;
            }

            @Override
            double variationTarget() {
                return 2e-4;
            }
        }.run();
    }

    public static class BatchCompletionListener implements AsyncHttpAppender.BatchCompletionListener {

        volatile Logger log = StatusLogger.getLogger();

        @Override
        public void onBatchCompletionEvent(AsyncHttpAppender.BatchCompletionEvent event) {
            AsyncHttpAppender.logBatchCompletionEvent(log, BENCHMARK_SFM_MARKER, event, ForkJoinPool.commonPool());
        }
    }

    static abstract class BenchmarkTemplate {
        final String uuid = UUID.randomUUID().toString();

        abstract String log4jConfigLocation();

        abstract int parallelism();

        abstract double variationTarget();

        void setupBackend() throws Exception {

        }

        void shutdown() throws Exception {

        }

        void logInfoMessage(Logger log, int threadId, long threadLocalSequence) {
            log.info("[uuid={}, t={}, s={}] A prefix for king & country: {}", uuid, threadId, threadLocalSequence, RandomStringUtils.insecure().nextAlphanumeric(10, 30));
        }

        void run() throws Exception {
            setupBackend();

            var lastLinesLogged = new MutableLong(0);
            var logEvents = new LongAdder();
            var stop = new AtomicBoolean();
            var avgLogEventsPerSec = new MutableDouble(-1);

            try (var context = TestHelpers.loggerContextFromTestResource(log4jConfigLocation())) {
                var log = context.getLogger(getClass());
                var overflowCountingAppender = TestHelpers.findAppender(context, CountingAppender.class);

                var batchCompletionListener = (BatchCompletionListener) context.getConfiguration().<AsyncHttpAppender>getAppender("AsyncHttp").batchCompletionListener();
                batchCompletionListener.log = log;

                IntFunction<Runnable> logTillStopped = threadId -> () -> {
                    var sequence = 0L;
                    while (!stop.get()) {
                        logInfoMessage(log, threadId, sequence++);
                        logEvents.increment();
                    }
                };

                var checkAndUpdateStatsScheduleHolder = new MutableObject<ScheduledFuture<?>>();
                Runnable checkAndUpdateStatistics = () -> {
                    var currentLinesLogged = logEvents.longValue();
                    var newLinesLogged = currentLinesLogged - lastLinesLogged.longValue();

                    if (avgLogEventsPerSec.doubleValue() < 0) {
                        avgLogEventsPerSec.setValue(newLinesLogged);
                    } else {
                        var avg0 = avgLogEventsPerSec.doubleValue();
                        avgLogEventsPerSec.setValue(0.9 * avg0 + 0.1 * newLinesLogged);

                        if (Math.abs(avg0 / avgLogEventsPerSec.doubleValue() - 1.0) < variationTarget()) {
                            out.printf("Found steady state at %s logs per second (droppedLogEvents=%s)%n",
                                    avgLogEventsPerSec.doubleValue(), overflowCountingAppender.currentCount());
                            out.printf("Stopping benchmark%n");

                            checkAndUpdateStatsScheduleHolder.getValue().cancel(false);
                            checkAndUpdateStatsScheduleHolder.setValue(null);
                            stop.set(true);
                            return;
                        }
                    }

                    var droppedLogEvents = overflowCountingAppender.currentCount();
                    out.printf("avgLogsPerSecond=%s, droppedLogEvents=%s%n", avgLogEventsPerSec, droppedLogEvents);
                    ForkJoinPool.commonPool().execute(() ->
                            log.info(BENCHMARK_SFM_MARKER, "avgLogsPerSecond={}, droppedLogEvents={}", avgLogEventsPerSec, droppedLogEvents));

                    lastLinesLogged.setValue(currentLinesLogged);
                };

                checkAndUpdateStatsScheduleHolder.setValue(
                        SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(checkAndUpdateStatistics, 1, 1, TimeUnit.SECONDS));

                var logJobs = IntStream.range(0, parallelism())
                        .mapToObj(threadId -> CompletableFuture.runAsync(logTillStopped.apply(threadId)))
                        .toList();

                logJobs.forEach(CompletableFuture::join);
                out.printf("%s log events with uuid=%s out of which %s where dropped, which means that %s events should have been delivered%n",
                        logEvents.longValue(), uuid, overflowCountingAppender.currentCount(), logEvents.longValue() - overflowCountingAppender.currentCount());
            } finally {
                shutdown();
            }
        }
    }

    static abstract class WiremockBenchmarkTemplate extends BenchmarkTemplate {
        final WireMockServer wireMockServer = new WireMockServer(options().dynamicPort().maxRequestJournalEntries(100));
        final String wireMockPath = "/logs/" + getClass().getSimpleName();

        ScheduledFuture<?> wireMockReconfigSchedule;

        @Override
        String log4jConfigLocation() {
            return "AsyncHttpAppenderNonJmhBenchmarks.wireMockAggressiveHttpTimeouts.xml";
        }

        @Override
        void setupBackend() {
            wireMockServer.start();
            var wireMockHttpUrl = "http://localhost:" + wireMockServer.port() + wireMockPath;
            System.setProperty("wireMockHttpUrl", wireMockHttpUrl);
            out.printf("WireMock is listening at %s%n", wireMockHttpUrl);

            Runnable configureWiremock = () -> {
                var stubConfiguration = post(urlEqualTo(wireMockPath));
                configureMappingWithRandomFailuresAndTimeouts(
                        stubConfiguration, triggerTimeoutMs(), simulatedFailureRate(), simulatedMedianResponseTimeMs(), 1, 0.1, 0.01);

                wireMockServer.stubFor(stubConfiguration);
            };

            if (0 < simulatedFailureRate() && simulatedFailureRate() < 1) {
                wireMockReconfigSchedule = SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(configureWiremock, 1, 1, TimeUnit.MILLISECONDS);
            }

            configureWiremock.run();
        }

        double simulatedFailureRate() {
            return 0.0;
        }

        int simulatedMedianResponseTimeMs() {
            return 0;
        }

        int triggerTimeoutMs() {
            return 11_000;
        }

        @Override
        void shutdown() {
            if (wireMockReconfigSchedule != null) {
                wireMockReconfigSchedule.cancel(false);
                wireMockReconfigSchedule = null;
            }

            wireMockServer.verify(moreThan(10), postRequestedFor(urlEqualTo(wireMockPath)));
            wireMockServer.stop();
        }
    }

}
