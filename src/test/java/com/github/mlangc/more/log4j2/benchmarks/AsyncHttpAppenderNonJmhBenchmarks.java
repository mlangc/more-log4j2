package com.github.mlangc.more.log4j2.benchmarks;

import com.github.mlangc.more.log4j2.appenders.AsyncHttpAppender;
import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.exception.UncheckedInterruptedException;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.util.Log4jThreadFactory;
import org.apache.logging.log4j.status.StatusLogger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.lang.System.out;


public class AsyncHttpAppenderNonJmhBenchmarks {
    private static final StatusLogger STATUS_LOGGER = StatusLogger.getLogger();

    public static void main(String[] args) throws Exception {
        new WiremockBenchmarkTemplate() {
            @Override
            int parallelism() {
                return 4;
            }

            @Override
            void configureWireMock(MappingBuilder mappingBuilder) {
                mappingBuilder.willReturn(ok());
            }
        }.run();
    }

    static abstract class BenchmarkTemplate {
        abstract String configLocation();
        abstract int parallelism();
        abstract void setupBackend() throws Exception;

        abstract void shutdown() throws Exception;

        void logInfoMessage(Logger log) {
            var logMessage = "A prefix for king & country: " + RandomStringUtils.insecure().nextAlphanumeric(10, 30);
            log.info(logMessage);
        }

        void run() throws Exception {
            setupBackend();

            var logLines = new LongAdder();
            var stop = new AtomicBoolean();
            var logsPerSecond0 = 100_000;
            var incrementLogsPerStep = 100_000;
            var nextStep = new MutableInt(1);
            var permits = new Semaphore(logsPerSecond0);

            var lastLogLines = new MutableLong(0);
            var lastRecordedLogsPerSecondRate = new MutableLong(-1);

            Runnable startNextStep = () -> {
                var currentLogLines = logLines.longValue();
                var newLogLines = currentLogLines - lastLogLines.longValue();
                lastRecordedLogsPerSecondRate.setValue(newLogLines);
                lastLogLines.setValue(currentLogLines);

                var newPerSecondTarget = logsPerSecond0 + nextStep.intValue() * incrementLogsPerStep;

                out.printf("Trying %s logs per second; last achieved rate at %s%n", newPerSecondTarget, lastRecordedLogsPerSecondRate.longValue());

                var lastPerSecondTarget = newPerSecondTarget - incrementLogsPerStep;
                if (lastRecordedLogsPerSecondRate.longValue() * 1.1 < lastPerSecondTarget) {
                    out.printf("Stopping the benchmark, since the requested log rates cannot be sustained%n");
                    stop.set(true);
                }

                permits.release(newPerSecondTarget);
                nextStep.increment();
            };

            var executor = Executors.newScheduledThreadPool(1, Log4jThreadFactory.createDaemonThreadFactory(getClass().getSimpleName()));
            ScheduledFuture<?> refillPermitsSchedule = null;
            try {
                refillPermitsSchedule = executor.scheduleAtFixedRate(startNextStep, 1, 1, TimeUnit.SECONDS);

                STATUS_LOGGER.registerListener(STATUS_LOGGER.getFallbackListener());
                STATUS_LOGGER.registerListener(AsyncHttpAppender.newDroppedBatchListener(() -> stop.set(true)));
                try (var context = TestHelpers.loggerContextFromTestResource(configLocation())) {
                    var log = context.getLogger(getClass());

                    Runnable loggerLoop = () -> {
                        try {
                            while (!stop.get()) {
                                if (permits.tryAcquire(1, TimeUnit.MILLISECONDS)) {
                                    logInfoMessage(log);
                                    logLines.increment();
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new UncheckedInterruptedException(e);
                        }
                    };

                    IntStream.range(0, parallelism())
                            .mapToObj(ignore -> CompletableFuture.runAsync(loggerLoop))
                            .toList()
                            .forEach(CompletableFuture::join);
                } finally {
                    STATUS_LOGGER.reset();
                }
            } finally {
                if (refillPermitsSchedule != null) {
                    refillPermitsSchedule.cancel(false);
                }

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
        String configLocation() {
            return "AsyncHttpAppenderTest.wireMockPerfTestVanilla.xml";
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

        abstract void configureWireMock(MappingBuilder mappingBuilder);

        @Override
        void shutdown() {
            wireMockServer.verify(moreThan(10), postRequestedFor(urlEqualTo(wireMockPath)));
            wireMockServer.stop();
        }
    }
}
