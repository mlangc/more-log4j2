package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.test.junit.LoggerContextSource;
import org.apache.logging.log4j.core.test.junit.Named;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.mlangc.more.log4j2.filters.TestHelpers.logWithAllOverloads;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.assertj.core.data.Percentage.withPercentage;

@LoggerContextSource("FastBurstFilterCompatTest.xml")
class FastBurstFilterCompatTest {
    private final CountingAppender burstAppender;
    private final CountingAppender fastBurstAppender;
    private final Logger burstLog;
    private final Logger fastBurstLog;

    FastBurstFilterCompatTest(
            final LoggerContext context,
            @Named("CountingAppender.Burst") CountingAppender burstAppender,
            @Named("CountingAppender.FastBurst") CountingAppender fastBurstAppender) {
        assumeThat(context.getConfiguration().getName()).contains(getClass().getSimpleName());
        assumeThat(burstAppender).isNotNull();
        assumeThat(fastBurstAppender).isNotNull();

        this.burstAppender = burstAppender;
        this.fastBurstAppender = fastBurstAppender;
        this.burstLog = context.getLogger(getClass().getCanonicalName() + ".Burst");
        this.fastBurstLog = context.getLogger(getClass().getCanonicalName() + ".FastBurst");
    }

    @BeforeEach
    void beforeEach() {
        burstAppender.clear();
        fastBurstAppender.clear();
    }

    @Test
    void configurationShouldInitializeProperly() {
        assertThat(burstAppender).isNotNull();
        assertThat(fastBurstAppender).isNotNull();
        assertThat(burstLog).isNotNull();
        assertThat(fastBurstLog).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({"1, 50", "2, 50", "2, 75", "1, 250", "4, 200", "4, 180", "4, 120", "8, 123"})
    void fastAndOriginalBurstFilterImplShouldResultInAlmostIdenticalLogCounts(int parallelism, int millis) throws InterruptedException {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stop = new AtomicBoolean(false);
            Consumer<Logger> logTillStop = log -> {
                while (!stop.get()) {
                    logWithAllOverloads(log, null, "test");
                }
            };

            var futures = Stream.of(burstLog, fastBurstLog)
                    .flatMap(log ->
                        IntStream.range(0, parallelism)
                                .mapToObj(ignore -> CompletableFuture.runAsync(() -> logTillStop.accept(log), executor))
                    ).toList();

            Thread.sleep(millis);
            stop.set(true);
            futures.forEach(CompletableFuture::join);

            var burstCounts = burstAppender.currentCount();
            var fastBurstCounts = fastBurstAppender.currentCount();
            assertThat(fastBurstCounts).isCloseTo(burstCounts, withPercentage(1e-3));
        }
    }
}
