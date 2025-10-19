package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.RepeatedTest;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

public class FastBurstFilterTest {
    @RepeatedTest(100)
    void filterShouldWorkIfConfiguredToAcceptOneLogPerMilli() throws InterruptedException {
        var burstFilter = new FastBurstFilter(Level.WARN, 1000, 1, Filter.Result.ACCEPT, Filter.Result.DENY);
        var event = new Log4jLogEvent("test", null, getClass().getCanonicalName(), Level.INFO, new SimpleMessage("test"), List.of(), null);

        var stop = new AtomicBoolean(false);

        record NanosWithCount(long t0, long t1, long count) {
            NanosWithCount merge(NanosWithCount other) {
                return new NanosWithCount(Math.min(t0, other.t0), Math.max(t1, other.t1), NanosWithCount.this.count + other.count);
            }

            long elapsedMillis() {
                return TimeUnit.NANOSECONDS.toMillis(t1 - t0);
            }
        }

        Supplier<NanosWithCount> countAcceptTillStopped = () -> {
            var t0 = System.nanoTime();
            var count = 0L;
            while (!stop.get()) {
                if (burstFilter.filter(event) == Filter.Result.ACCEPT) {
                    count++;
                }
            }

            return new NanosWithCount(t0, System.nanoTime(), count);
        };

        var futures = IntStream.range(0, 2)
                .mapToObj(ignore -> CompletableFuture.supplyAsync(countAcceptTillStopped))
                .toList();

        Thread.sleep(15);
        stop.set(true);
        var mergedNanosWithCount = futures.stream().map(CompletableFuture::join).reduce(NanosWithCount::merge).orElseThrow();
        assertThat(mergedNanosWithCount.count).isBetween(mergedNanosWithCount.elapsedMillis(), mergedNanosWithCount.elapsedMillis() + 2);
    }
}
