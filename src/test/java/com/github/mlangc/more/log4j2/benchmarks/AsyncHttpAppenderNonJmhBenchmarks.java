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
import com.github.mlangc.more.log4j2.test.helpers.CountingAppender;
import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.status.StatusLogger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.lang.System.out;


public class AsyncHttpAppenderNonJmhBenchmarks {
    static final Marker BENCHMARK_SFM_MARKER = MarkerManager.getMarker("BenchmarkSfm");

    private static final ScheduledExecutorService SCHEDULED_EXECUTOR_SERVICE = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setDaemon(true).setNameFormat(AsyncHttpAppenderNonJmhBenchmarks.class.getSimpleName() + ":%d").build());

    public static void main(String[] args) throws Exception {
        System.setProperty("benchmarkLog4jPattern", "%d{HH:mm:ss.SSS} %-5level %c{2}@[%t] - %msg%n");

        new BenchmarkTemplate() {
            @Override
            String log4jConfigLocation() {
                return "AsyncHttpAppenderNonJmhBenchmarks.datadogOptimized.xml";
            }

            @Override
            int parallelism() {
                return 4;
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

            var lastLinesLogged = new MutableLong(0);
            var logEvents = new LongAdder();
            var stop = new AtomicBoolean();
            var avgLogEventsPerSec = new MutableDouble(-1);

            try (var context = TestHelpers.loggerContextFromTestResource(log4jConfigLocation())) {
                var log = context.getLogger(getClass());
                var overflowCountingAppender = TestHelpers.findAppender(context, CountingAppender.class);

                var batchCompletionListener = (BatchCompletionListener) context.getConfiguration().<AsyncHttpAppender>getAppender("AsyncHttp").batchCompletionListener();
                batchCompletionListener.log = log;

                Runnable logTillStopped = () -> {
                    var sequence = 0L;
                    while (!stop.get()) {
                        logInfoMessage(log, sequence++);
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

                        if (Math.abs(avg0 / avgLogEventsPerSec.doubleValue() - 1.0) < 5e-4) {
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
                        .mapToObj(ignore -> CompletableFuture.runAsync(logTillStopped))
                        .toList();

                logJobs.forEach(CompletableFuture::join);
            }
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
