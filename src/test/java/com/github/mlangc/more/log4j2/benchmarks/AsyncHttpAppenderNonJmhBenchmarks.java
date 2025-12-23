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
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.exception.UncheckedInterruptedException;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.util.Log4jThreadFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.lang.System.out;


public class AsyncHttpAppenderNonJmhBenchmarks {
    public static void main(String[] args) throws Exception {
        System.setProperty("benchmarkLog4jPattern", "%d{HH:mm:ss.SSS} %-5level %c{2}@[%t] - %msg%n");

        new BenchmarkTemplate() {
            @Override
            LoadCfg loadConfig() {
                return new LoadCfg(100_000, 10_000);
            }

            @Override
            String log4jConfigLocation() {
                return "AsyncHttpAppenderNonJmhBenchmarks.datadogOptimized.xml";
            }

            @Override
            int parallelism() {
                return 1;
            }
        }.run();
    }

    public static class BatchCompletionListener implements AsyncHttpAppender.BatchCompletionListener {
        private static final Logger LOG = LogManager.getLogger(BatchCompletionListener.class);
        static volatile AtomicBoolean stopFlag;

        @Override
        public void onBatchCompletionEvent(AsyncHttpAppender.BatchCompletionEvent completionEvent) {
            AsyncHttpAppender.logBatchCompletionEvent(LOG, ForkJoinPool.commonPool(), completionEvent);

            if (completionEvent.completionType() instanceof AsyncHttpAppender.BatchDropped) {
                var localStopFlag = stopFlag;

                if (localStopFlag.compareAndSet(false, true)) {
                    out.printf("Stopping benchmark since a batch has been dropped%n");
                }
            }
        }
    }

    static abstract class BenchmarkTemplate {
        record LoadCfg(int logsPerSecond0, int logsPerSecondIncrement) { }

        LoadCfg loadConfig() {
            return new LoadCfg(5000, 1000);
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

            var loadConfig = loadConfig();
            var logLines = new LongAdder();
            var stop = new AtomicBoolean();
            BatchCompletionListener.stopFlag = stop;

            var nextStep = new MutableInt(1);
            var permits = new Semaphore(loadConfig.logsPerSecond0);

            var lastLogLines = new MutableLong(0);
            var lastRecordedLogsPerSecondRate = new MutableLong(-1);

            Runnable startNextStep = () -> {
                if (stop.get()) {
                    return;
                }

                var currentLogLines = logLines.longValue();
                var newLogLines = currentLogLines - lastLogLines.longValue();
                lastRecordedLogsPerSecondRate.setValue(newLogLines);
                lastLogLines.setValue(currentLogLines);

                var newPerSecondTarget = loadConfig.logsPerSecond0 + nextStep.intValue() * loadConfig.logsPerSecondIncrement;
                var lastPerSecondTarget = newPerSecondTarget - loadConfig.logsPerSecondIncrement;

                out.printf("Trying %s logs per second; last achieved rate at %s%n", newPerSecondTarget, newLogLines);

                if (lastRecordedLogsPerSecondRate.longValue() * 1.1 < lastPerSecondTarget) {
                    out.printf("Stopping the benchmark, since the requested log rates cannot be sustained%n");
                    stop.set(true);
                }

                permits.release(newPerSecondTarget);
                nextStep.increment();
            };

            var executor = Executors.newScheduledThreadPool(1, Log4jThreadFactory.createDaemonThreadFactory(getClass().getSimpleName()));
            ScheduledFuture<?> refillPermitsSchedule = null;
            try (var context = TestHelpers.loggerContextFromTestResource(log4jConfigLocation())) {
                out.printf("Loaded context with config %s%n", context.getConfiguration());

                var log = context.getLogger(getClass());

                Runnable loggerLoop = () -> {
                    try {
                        out.printf("Started loggerLoop[%s]%n", Thread.currentThread().getName());

                        var sequence = 0L;
                        while (!stop.get()) {
                            if (permits.tryAcquire(1, TimeUnit.MILLISECONDS)) {
                                logInfoMessage(log, sequence++);
                                logLines.increment();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new UncheckedInterruptedException(e);
                    }
                };

                refillPermitsSchedule = executor.scheduleAtFixedRate(startNextStep, 1, 1, TimeUnit.SECONDS);
                IntStream.range(0, parallelism())
                        .mapToObj(ignore -> CompletableFuture.runAsync(loggerLoop))
                        .toList()
                        .forEach(CompletableFuture::join);
            } finally {
                if (refillPermitsSchedule != null) {
                    refillPermitsSchedule.cancel(false);
                }

                BatchCompletionListener.stopFlag = null;
                executor.shutdown();
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
