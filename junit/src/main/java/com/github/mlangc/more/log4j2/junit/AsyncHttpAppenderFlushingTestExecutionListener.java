/*-
 * #%L
 * more-log4j2
 * %%
 * Copyright (C) 2025 - 2026 Matthias Langer
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
package com.github.mlangc.more.log4j2.junit;

import com.github.mlangc.more.log4j2.appenders.AsyncHttpAppender;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class AsyncHttpAppenderFlushingTestExecutionListener implements TestExecutionListener {
    private static final String PROPERTY_PREFIX = "moreLog4j2.asyncHttpAppenderFlushingTestExecutionListener";
    private static final String ENV_PREFIX = "MORE_LOG4J2_ASYNC_HTTP_APPENDER_FLUSHING_TEST_EXECUTION_LISTENER";

    private enum ConfigProperty {
        ENABLED("Enabled", "ENABLED");

        final String propertySuffix;
        final String envSuffix;

        ConfigProperty(String propertySuffix, String envSuffix) {
            this.propertySuffix = propertySuffix;
            this.envSuffix = envSuffix;
        }

        String systemPropertyName() {
            return PROPERTY_PREFIX + propertySuffix;
        }

        String envVarName() {
            return ENV_PREFIX + "_" + envSuffix;
        }

        String load(String defaultValue) {
            var ret = System.getProperty(systemPropertyName());
            if (ret != null) {
                return ret;
            }

            ret = System.getenv(envVarName());
            if (ret != null) {
                return ret;
            }

            return defaultValue;
        }

        @Override
        public String toString() {
            return "[sys=" + systemPropertyName() + ", env=" + envVarName() + "]";
        }
    }

    private final boolean enabled = "true".equals(ConfigProperty.ENABLED.load("true"));

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (!enabled) {
            return;
        }

        var asyncAppenders = LoggerContext.getContext(false).getConfiguration().getAppenders().values().stream()
                .flatMap(appender -> {
                    if (appender instanceof AsyncHttpAppender asyncHttpAppender) return Stream.of(asyncHttpAppender);
                    else return Stream.empty();
                }).toList();

        var maxTimeoutMillis = asyncAppenders.stream().mapToInt(AsyncHttpAppender::shutdownTimeoutMs).max().orElse(0);

        CompletableFuture.allOf(
                asyncAppenders.stream()
                        .map(appender -> appender.forceFlushAndAwaitTillPushed().orTimeout(maxTimeoutMillis, TimeUnit.MILLISECONDS))
                        .toArray(CompletableFuture<?>[]::new)).join();
    }
}
