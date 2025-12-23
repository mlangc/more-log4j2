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
