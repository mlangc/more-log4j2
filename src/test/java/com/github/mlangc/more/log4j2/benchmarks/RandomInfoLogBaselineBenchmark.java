package com.github.mlangc.more.log4j2.benchmarks;

import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@Fork(1)
@Warmup(iterations = 3, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
public class RandomInfoLogBaselineBenchmark {
    private LoggerContext context;
    private Logger log;

    @Setup
    public void setup() {
        context = TestHelpers.loggerContextFromTestResource("RandomInfoLogBaselineBenchmark.xml");
        log = context.getLogger(getClass());
    }

    @TearDown
    public void tearDown() {
        context.close();
    }

    @Benchmark
    public void log() {
        var logMessage = "A prefix for king & country: " + RandomStringUtils.insecure().nextAlphanumeric(10, 30);
        log.info(logMessage);
    }
}
