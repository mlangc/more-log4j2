package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.test.junit.LoggerContextSource;
import org.apache.logging.log4j.core.test.junit.Named;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@LoggerContextSource("MarkerBasedLogThrottlingUseCaseTest.xml")
class MarkerBasedLogThrottlingUseCaseTest {
    static final Marker THROTTLED_1 = MarkerManager.getMarker("throttled1");
    static final Marker THROTTLED_10 = MarkerManager.getMarker("throttled10");

    private final LoggerContext loggerContext;
    private final CountingAppender countingAppender;
    private final Logger log;

    MarkerBasedLogThrottlingUseCaseTest(LoggerContext loggerContext, @Named("CountingAppender") CountingAppender countingAppender) {
        this.loggerContext = loggerContext;
        this.countingAppender = countingAppender;
        this.log = loggerContext.getLogger(getClass());
    }

    @AfterEach
    void afterEach() {
        countingAppender.clear();
    }

    @Test
    void shouldParseAndUseRightConfig() {
        assertThat(loggerContext.getName()).contains(getClass().getSimpleName());
        assertThat(countingAppender).isNotNull();
    }

    @Test
    void shouldThrottleLogsBasedOnMarkers() throws InterruptedException {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stop = new AtomicBoolean();
            Runnable logTillStopped = () -> {
                while (!stop.get()) {
                    log.info(THROTTLED_1, "at most once per second");
                    log.info(THROTTLED_10, "at most 10 times per second");
                }
            };

            var futures = IntStream.range(0, 2)
                    .mapToObj(ignore -> CompletableFuture.runAsync(logTillStopped, executor))
                    .toList();

            Thread.sleep(500);
            stop.set(true);

            assertThat(futures).allSatisfy(f -> assertThat(f).succeedsWithin(1, TimeUnit.MILLISECONDS));
            assertThat(countingAppender.currentCountWithoutMarker()).isZero();

            var countsWithMarker = countingAppender.currentCountsWithMarkers();
            assertThat(countsWithMarker.getOrDefault(THROTTLED_1.getName(), 0L))
                    .isNotZero()
                    .isLessThan(countsWithMarker.getOrDefault(THROTTLED_10.getName(), 0L));
        }
    }
}
