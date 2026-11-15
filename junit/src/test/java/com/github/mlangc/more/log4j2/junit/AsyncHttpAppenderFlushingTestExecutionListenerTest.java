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

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

class AsyncHttpAppenderFlushingTestExecutionListenerTest {
    private static final Logger LOG = LogManager.getLogger(AsyncHttpAppenderFlushingTestExecutionListenerTest.class);
    private static final String ENABLED_PROPERTY = "moreLog4j2.asyncHttpAppenderFlushingTestExecutionListenerEnabled";

    @RegisterExtension
    static WireMockExtension wireMockExt = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    String wireMockPath;
    String wireMockHttpUrl;

    @BeforeEach
    void beforeEach(TestInfo testInfo) {
        // Note: Each test uses a unique path to exclude unexpected interactions between individual tests.
        wireMockPath = "/logs/" + testInfo.getTestMethod().orElseThrow().getName();
        wireMockHttpUrl = "http://localhost:" + wireMockExt.getPort() + wireMockPath;
    }

    @AfterEach
    void afterEach() {
        // Back to log4j2-test.xml; this also stops the appender under test.
        LoggerContext.getContext(false).setConfigLocation(null);
        System.clearProperty(ENABLED_PROPERTY);
    }

    @Test
    void shouldFlushWhenTestPlanExecutionFinished() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));
        configureDefaultContextWithAsyncHttpAppender(15_000);

        LOG.info("Test");
        wireMockExt.verify(0, postRequestedFor(urlEqualTo(wireMockPath)));

        new AsyncHttpAppenderFlushingTestExecutionListener().testPlanExecutionFinished(null);

        // No waiting here: the listener must not return before the batch has been pushed.
        wireMockExt.verify(1, postRequestedFor(urlEqualTo(wireMockPath)).withRequestBody(equalTo("Test")));
    }

    @Test
    void shouldNotFlushIfDisabled() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));
        configureDefaultContextWithAsyncHttpAppender(15_000);
        System.setProperty(ENABLED_PROPERTY, "false");

        LOG.info("Test");
        new AsyncHttpAppenderFlushingTestExecutionListener().testPlanExecutionFinished(null);

        wireMockExt.verify(0, postRequestedFor(urlEqualTo(wireMockPath)));
    }

    @Test
    void shouldGiveUpAfterShutdownTimeout() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok().withFixedDelay(2000)));
        configureDefaultContextWithAsyncHttpAppender(100);

        LOG.info("Test");

        var listener = new AsyncHttpAppenderFlushingTestExecutionListener();
        assertThatThrownBy(() -> listener.testPlanExecutionFinished(null))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void shouldBeRegisteredWithTheLauncherAndFlushAfterTheTestPlan() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));
        configureDefaultContextWithAsyncHttpAppender(15_000);

        // The launcher loads the listener under test via ServiceLoader, like a build tool or an IDE would.
        var summaryListener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.execute(request().selectors(selectClass(LoggingTestCase.class)).build(), summaryListener);

        assertThat(summaryListener.getSummary().getTestsSucceededCount()).isOne();
        wireMockExt.verify(1, postRequestedFor(urlEqualTo(wireMockPath)).withRequestBody(equalTo("Logged by nested test plan")));
    }

    /**
     * Executed by {@link #shouldBeRegisteredWithTheLauncherAndFlushAfterTheTestPlan()} in a test plan of its own.
     */
    static class LoggingTestCase {
        @Test
        void shouldLog() {
            LOG.info("Logged by nested test plan");
        }
    }

    private void configureDefaultContextWithAsyncHttpAppender(int shutdownTimeoutMs) {
        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("lingerMs", 3_600_000) // <-- nothing is sent unless somebody forces a flush
                        .addAttribute("retries", 0)
                        .addAttribute("shutdownTimeoutMs", shutdownTimeoutMs)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        // The listener under test looks at the default context, so we must not create a new one here.
        LoggerContext.getContext(false).reconfigure(configBuilder.build(false));
    }
}
