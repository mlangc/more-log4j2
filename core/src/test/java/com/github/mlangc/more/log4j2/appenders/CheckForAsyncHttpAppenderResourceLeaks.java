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
package com.github.mlangc.more.log4j2.appenders;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class CheckForAsyncHttpAppenderResourceLeaks implements AfterAllCallback {
    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        assertNoThreadLeaks(context);
    }

    private static void assertNoThreadLeaks(ExtensionContext context) {
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var mxBean = ManagementFactory.getThreadMXBean();
            assertThat(mxBean.dumpAllThreads(false, false))
                    .filteredOn(t -> t.getThreadName().contains(AsyncHttpAppender.class.getSimpleName()))
                    .as("context=%s", context.getDisplayName())
                    .isEmpty();
        });
    }
}
