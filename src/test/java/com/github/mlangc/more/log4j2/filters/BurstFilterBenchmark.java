package com.github.mlangc.more.log4j2.filters;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@Fork(1)
@Warmup(iterations = 3, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
public class BurstFilterBenchmark {
    static final Logger LOG = LogManager.getLogger(BurstFilterBenchmark.class);
    static final Logger BURST_LOG = LogManager.getLogger(BurstFilterBenchmark.class.getCanonicalName() + ".Burst");
    static final Logger FAST_BURST_LOG = LogManager.getLogger(BurstFilterBenchmark.class.getCanonicalName() + ".FastBurst");

    static final Marker BURST_MARKER = MarkerManager.getMarker("std_burst");
    static final Marker FAST_BURST_MARKER = MarkerManager.getMarker("fst_burst");

    static void main() throws RunnerException {
        new Runner(new OptionsBuilder().resultFormat(ResultFormatType.JSON).build()).run();
    }

    //@Benchmark
    public void logDebug() {
        LOG.debug("test");
    }

    @Benchmark
    public void burstLogInfo() {
        BURST_LOG.info("test");
    }

    @Benchmark
    public void fastBurstLogInfo() {
        FAST_BURST_LOG.info("test");
    }

    @Benchmark
    public void logBurstInfo() {
        LOG.info(BURST_MARKER, "test");
    }

    @Benchmark
    public void logFastBurstInfo() {
        LOG.info(FAST_BURST_MARKER, "test");
    }
}
