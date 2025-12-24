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

import java.util.List;

public class IngestLogsViaHttpDemo {
    private static final List<String> DEMO_CONFIG_PATHS = List.of(
            "IngestLogsViaHttpDemo.grafanaLokiV1PushAsyncHttpConfig.xml"/*,
            "IngestLogsViaHttpDemo.grafanaLokiV1PushHttpConfig.xml",
            "IngestLogsViaHttpDemo.grafanaOltpAsyncHttpConfig.xml",
            "IngestLogsViaHttpDemo.datadogHttpConfig.xml",
            "IngestLogsViaHttpDemo.datadogAsyncHttpConfig.xml",
            "IngestLogsViaHttpDemo.dynatraceHttpConfig.xml",
            "IngestLogsViaHttpDemo.dynatraceAsyncHttpConfig.xml"*/
    );

    public static void main(String[] args) {
        for (var demoConfigPath : DEMO_CONFIG_PATHS) {
            try (LoggerContext context = TestHelpers.loggerContextFromTestResource(demoConfigPath)) {
                int numLogs = 100;

                var log = context.getLogger(IngestLogsViaHttpDemo.class);

                for (int logs = 0; logs < numLogs; logs++) {
                    log.info("{} log messages so far have been logged using {}", logs + 1, demoConfigPath);
                }

                log.info("All done with demo config {}", demoConfigPath);
            }
        }
    }
}
