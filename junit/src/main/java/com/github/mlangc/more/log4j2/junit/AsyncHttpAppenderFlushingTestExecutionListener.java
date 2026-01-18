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
import org.apache.logging.log4j.status.StatusLogger;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class AsyncHttpAppenderFlushingTestExecutionListener implements TestExecutionListener {
    private static final String PROPERTY_PREFIX = "moreLog4j2.asyncHttpAppenderFlushingTestExecutionListener";
    private static final String ENV_PREFIX = "MORE_LOG4J2_ASYNC_HTTP_APPENDER_FLUSHING_TEST_EXECUTION_LISTENER";

    private enum ConfigProperty {
        ENABLED("Enabled", "ENABLED"), TIMEOUT_MS("TimeoutMs", "TIMEOUT_MS");

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
    private final int timeoutMs = getTimeoutMs();

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (!enabled) {
            return;
        }

        LoggerContext.getContext(false).getConfiguration().getAppenders().values().forEach(appender -> {
            if (appender instanceof AsyncHttpAppender asyncHttpAppender) {
                try {
                    asyncHttpAppender.forceFlushAndAwaitTillPushed().orTimeout(timeoutMs, TimeUnit.MILLISECONDS).join();
                } catch (Exception e) {
                    StatusLogger.getLogger().error("Error flushing logs of {}", asyncHttpAppender, e);
                }
            }
        });
    }

    private static String firstCharacterToLower(String s) {
        if (s.isEmpty()) {
            return s;
        }

        return s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1);
    }

    private static int getTimeoutMs() {
        int defaultTimeoutMs = 5000;
        try {
            var timeoutMs = Integer.parseInt(ConfigProperty.TIMEOUT_MS.load("" + defaultTimeoutMs));
            if (timeoutMs < 0) {
                StatusLogger.getLogger().error("Invalid value {} for property {}", timeoutMs, ConfigProperty.TIMEOUT_MS);
            }
            return timeoutMs;
        } catch (NumberFormatException e) {
            StatusLogger.getLogger().error("Cannot parse value for property {}", ConfigProperty.TIMEOUT_MS, e);
            return defaultTimeoutMs;
        }
    }
}
