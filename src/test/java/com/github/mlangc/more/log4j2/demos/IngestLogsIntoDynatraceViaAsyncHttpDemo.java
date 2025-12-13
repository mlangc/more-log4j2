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
package com.github.mlangc.more.log4j2.demos;

import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.spi.ExtendedLogger;

import java.time.Duration;

public class IngestLogsIntoDynatraceViaAsyncHttpDemo {
    public static void main(String[] args) {
        try (LoggerContext loggerContext = TestHelpers.loggerContextFromTestResource("IngestLogsViaAsyncHttpAppenderConfig.xml")) {
            ExtendedLogger log = loggerContext.getLogger(IngestLogsIntoDynatraceViaAsyncHttpDemo.class);

            long totalNanos = 0L;
            int numLogs = 10_000;

            long t0 = System.nanoTime();
            for (int logs = 0; logs < numLogs; logs++) {
                log.info("{} log messages so far have been logged", logs + 1);
            }

            totalNanos += System.nanoTime() - t0;
            double avgLogsPerSec = (double) numLogs / (totalNanos * 1e-9);
            log.info("logged {} logs in {} at {} logs per second", numLogs, Duration.ofNanos(totalNanos), avgLogsPerSec);
        }
    }
}
