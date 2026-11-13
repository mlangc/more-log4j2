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

import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproducers for the findings in doc/reviews/2026-11-12-review.md. Each test fails against the reviewed
 * commit and is disabled for that reason; remove the {@link Disabled} annotation once the finding is fixed.
 */
class Review20261112ReproducersTest {
    private static final String REVIEW = "doc/reviews/2026-11-12-review.md";

    @RegisterExtension
    static WireMockExtension wireMockExt = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    public static class RecordingListener implements AsyncHttpAppender.BatchCompletionListener {
        final List<AsyncHttpAppender.BatchCompletionEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onBatchCompletionEvent(AsyncHttpAppender.BatchCompletionEvent event) {
            events.add(event);
        }

        int deliveredLogEvents() {
            return events.stream().mapToInt(e -> e.stats().batchLogEvents()).sum();
        }
    }

    String wireMockPath;
    String wireMockHttpUrl;

    @BeforeEach
    void beforeEach(TestInfo testInfo) {
        // Note: Each test uses a unique path to exclude unexpected interactions between individual tests.
        wireMockPath = "/review/" + testInfo.getTestMethod().orElseThrow().getName();
        wireMockHttpUrl = "http://localhost:" + wireMockExt.getPort() + wireMockPath;
    }

    @Test
    void c1_lingerFlushShouldBeRetriedAfterBufferDrains() throws InterruptedException {
        var lingerMs = 20;
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok().withFixedDelay(8 * lingerMs)));

        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("lingerMs", lingerMs)
                        .addAttribute("maxBatchLogEvents", 2)
                        .addAttribute("maxConcurrentRequests", 1)
                        .addAttribute("maxBatchBufferBatches", 1)
                        .addAttribute("batchCompletionListener", RecordingListener.class.getName())
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        try (var context = TestHelpers.loggerContextFromConfig(configBuilder)) {
            var appender = TestHelpers.findAppender(context, AsyncHttpAppender.class);
            var listener = (RecordingListener) appender.batchCompletionListener();
            var log = context.getLogger(getClass());

            log.info("a1");
            log.info("a2");
            log.info("b1"); // <-- flushes batch A, which is sent immediately and stays in flight for 8 * lingerMs millis
            Thread.sleep(lingerMs * 2); // <-- the linger timer flushes "b1" as batch B, which stays buffered, since A occupies the only request slot
            log.info("b2");
            log.info("c1");

            // "b2" and "c1" now linger in the current batch. The linger timer fires while B still occupies the only buffer slot.
            // Batch A completes after ~8 * lingerMs; the lingering batch should be flushed as soon as B is sent and frees its slot.
            Awaitility.await().atMost(100 * lingerMs, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(listener.deliveredLogEvents()).isEqualTo(5));
        }
    }

    @Test
    @Disabled("Reproducer for finding C2 in " + REVIEW)
    void c2_typesExposedToBatchCompletionListenersShouldBePublic() {
        // BatchDeliveredSuccess & BatchDeliveredError are public records with an `HttpStatus` component. Listeners
        // outside this package cannot call `httpStatus().code()` unless `HttpStatus` is public as well.
        assertThat(Modifier.isPublic(HttpStatus.class.getModifiers())).isTrue();
    }

    @Test
    @Disabled("Reproducer for finding C3 in " + REVIEW)
    void c3_bufferedBatchShouldBeSentAsSoonAsRequestSlotIsFree() {
        var delayMs = 3000;
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok().withFixedDelay(delayMs)));

        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("maxBatchLogEvents", 1)
                        .addAttribute("maxConcurrentRequests", 1)
                        .addAttribute("shutdownTimeoutMs", 60_000)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        try (var context = TestHelpers.loggerContextFromConfig(configBuilder)) {
            var appender = TestHelpers.findAppender(context, AsyncHttpAppender.class);
            var log = context.getLogger(getClass());
            log.info("a");
            log.info("b");
            log.info("c");
            appender.forceFlushAndAwaitTillPushed();

            Awaitility.await().atMost(40, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
                    .until(() -> serveEventsForCurrentTest().size() == 3 && appender.forceFlushAndAwaitTillPushed().isDone());

            var requestMillis = serveEventsForCurrentTest().stream()
                    .map(e -> e.getRequest().getLoggedDate().getTime())
                    .sorted(Comparator.naturalOrder())
                    .toList();

            // Requests are served one at a time, so anything beyond `delayMs` between two requests is time during which
            // a batch was waiting in the buffer although a request slot was free.
            var maxIdleMs = 250;
            assertThat(requestMillis.get(1) - requestMillis.get(0) - delayMs).as("idle ms before 2nd request").isLessThan(maxIdleMs);
            assertThat(requestMillis.get(2) - requestMillis.get(1) - delayMs).as("idle ms before 3rd request").isLessThan(maxIdleMs);
        }
    }

    @Test
    @Disabled("Reproducer for finding S1 in " + REVIEW)
    void s1_invalidHeaderValueShouldNotBeLeakedToStatusLogger() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));
        var secret = "s3cr3t-token-value\n"; // <-- the trailing newline makes this an invalid header value

        var statusMessages = new CopyOnWriteArrayList<String>();
        var statusListener = new StatusListener() {
            @Override
            public void log(StatusData data) {
                statusMessages.add(data.getMessage().getFormattedMessage() + " / " + data.getThrowable());
            }

            @Override
            public Level getStatusLevel() {
                return Level.INFO;
            }

            @Override
            public void close() { }
        };

        StatusLogger.getLogger().registerListener(statusListener);
        try {
            var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
            configBuilder = configBuilder
                    .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                            .addAttribute("url", wireMockHttpUrl)
                            .addAttribute("retries", 0)
                            .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg"))
                            .addComponent(configBuilder.newProperty("Authorization", "Api-Token " + secret)))
                    .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

            try (var context = TestHelpers.loggerContextFromConfig(configBuilder)) {
                context.getLogger(getClass()).info("x");
            }
        } finally {
            StatusLogger.getLogger().removeListener(statusListener);
        }

        assertThat(statusMessages).isNotEmpty().noneMatch(m -> m.contains("s3cr3t"));
    }

    @Test
    @Disabled("Reproducer for finding S2 in " + REVIEW)
    void s2_hugeResponseBodyShouldNotBeRetained() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok().withBody("X".repeat(5_000_000))));

        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("batchCompletionListener", RecordingListener.class.getName())
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        RecordingListener listener;
        try (var context = TestHelpers.loggerContextFromConfig(configBuilder)) {
            listener = (RecordingListener) TestHelpers.findAppender(context, AsyncHttpAppender.class).batchCompletionListener();
            context.getLogger(getClass()).info("x");
        }

        assertThat(listener.events).singleElement().satisfies(evt ->
                assertThat(evt.type()).isInstanceOfSatisfying(AsyncHttpAppender.BatchDeliveredSuccess.class, success ->
                        assertThat(success.httpStatus().message().length()).isLessThan(100_000)));
    }

    @Test
    @Disabled("Reproducer for finding R1 in " + REVIEW)
    void r1_emptyFirstEventShouldNotBreakBatchSizeAccounting() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

        var maxBatchBytes = 3;
        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("maxBatchBytes", maxBatchBytes)
                        .addAttribute("batchSeparatorInsertionStrategy", "always")
                        .addAttribute("batchCompletionListener", RecordingListener.class.getName())
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        RecordingListener listener;
        try (var context = TestHelpers.loggerContextFromConfig(configBuilder)) {
            listener = (RecordingListener) TestHelpers.findAppender(context, AsyncHttpAppender.class).batchCompletionListener();
            var log = context.getLogger(getClass());
            log.info(""); // <-- serialized to zero bytes, which leaves `currentBatchBytes` at 0
            log.info("abc"); // <-- the separator in front of this event is therefore not accounted for
        }

        assertThat(listener.events).isNotEmpty().allSatisfy(evt ->
                assertThat(evt.stats().batchBytesUncompressed()).isLessThanOrEqualTo(maxBatchBytes));
    }

    private List<ServeEvent> serveEventsForCurrentTest() {
        return wireMockExt.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().equals(wireMockPath))
                .toList();
    }
}
