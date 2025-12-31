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
package com.github.mlangc.more.log4j2.appenders;

import com.github.mlangc.more.log4j2.appenders.AsyncHttpAppender.BatchSeparatorInsertionStrategy;
import com.github.mlangc.more.log4j2.appenders.AsyncHttpAppender.ContentEncoding;
import com.github.mlangc.more.log4j2.appenders.AsyncHttpAppender.HttpMethod;
import com.github.mlangc.more.log4j2.test.helpers.CountingAppender;
import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.LoggedResponse;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.filter.BurstFilter;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.lookup.JavaLookup;
import org.apache.logging.log4j.core.util.Log4jThreadFactory;
import org.apache.logging.log4j.status.StatusLogger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntToLongFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncHttpAppenderTest {
    private static final StatusLogger STATUS_LOGGER = StatusLogger.getLogger();

    static { LoggerFactory.getLogger(AsyncHttpAppenderTest.class); }

    static class SslConfigSupplier implements HttpClientSslConfigSupplier {
        @Override
        public HttpClientSslConfig get() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new WireMockHttpsTrustManager[]{new WireMockHttpsTrustManager()}, null);
            return new HttpClientSslConfig(sslContext, null);
        }
    }

    static class WireMockHttpsTrustManager extends X509ExtendedTrustManager {
        private final X509TrustManager defaultTrustManager;

        WireMockHttpsTrustManager() throws NoSuchAlgorithmException, KeyStoreException {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
            defaultTrustManager = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            defaultTrustManager.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain.length > 0 && chain[0].getIssuerX500Principal().toString().contains("Tom Akehurst") && chain[0].getSerialNumber().equals(BigInteger.valueOf(495529551))) {
                return;
            }

            defaultTrustManager.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return defaultTrustManager.getAcceptedIssuers();
        }
    }

    static class TrustLocalHostnameVerifier implements HostnameVerifier {
        private final HostnameVerifier defaultHostnameVerifier;

        TrustLocalHostnameVerifier() {
            defaultHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        }

        @Override
        public boolean verify(String hostname, SSLSession session) {
            return "localhost".equals(hostname) || defaultHostnameVerifier.verify(hostname, session);
        }
    }

    @RegisterExtension
    static WireMockExtension wireMockExt = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().dynamicHttpsPort())
            .build();

    @BeforeAll
    static void beforeAll() throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException {
        sequence = new AtomicLong();
        executor = new ScheduledThreadPoolExecutor(1, Log4jThreadFactory.createDaemonThreadFactory("test"));
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

        System.setProperty("wireMockPort", "" + wireMockExt.getPort());

        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, new TrustManager[]{new WireMockHttpsTrustManager()}, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

        if (!(HttpsURLConnection.getDefaultHostnameVerifier() instanceof TrustLocalHostnameVerifier)) {
            HttpsURLConnection.setDefaultHostnameVerifier(new TrustLocalHostnameVerifier());
        }
    }

    final List<ScheduledFuture<?>> schedulesToCancelAfterTest = new ArrayList<>();

    long testId;
    String wireMockPath;
    String wireMockHttpUrl;
    String wireMockHttpsUrl;

    @BeforeEach
    void beforeEach(TestInfo testInfo) {
        // Note: Each test uses unique paths to exclude the possibility, that there are unexpected interactions between
        // individual tests.

        testId = sequence.getAndIncrement();
        wireMockPath = "/logs" + testId;
        wireMockHttpUrl = "http://localhost:" + wireMockExt.getPort() + wireMockPath;
        wireMockHttpsUrl = "https://localhost:" + wireMockExt.getHttpsPort() + wireMockPath;

        System.setProperty("testId", "" + testId);
        System.setProperty("wireMockHttpUrl", wireMockHttpUrl);
        System.setProperty("wireMockHttpsUrl", wireMockHttpsUrl);

        STATUS_LOGGER.info("<".repeat(80));
        STATUS_LOGGER.info("About to execute '{}' with testId={}", testInfo.getDisplayName(), testId);
        STATUS_LOGGER.info("<".repeat(80));
    }

    @AfterEach
    void afterEach(TestInfo testInfo) {
        schedulesToCancelAfterTest.forEach(f -> f.cancel(false));

        STATUS_LOGGER.info(">".repeat(80));
        STATUS_LOGGER.info("Done executing '{}'", testInfo.getDisplayName());
        STATUS_LOGGER.info(">".repeat(80));
    }

    @AfterAll
    static void afterAll() throws InterruptedException {
        sequence = null;

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        executor = null;
    }

    static AtomicLong sequence;

    static ScheduledThreadPoolExecutor executor;

    static class TestBatchCompletionListener implements AsyncHttpAppender.BatchCompletionListener {
        private static final StatusLogger LOG = StatusLogger.getLogger();

        private final ArrayDeque<AsyncHttpAppender.BatchCompletionEvent> lastBatchCompletionEvents = new ArrayDeque<>();

        @Override
        public void onBatchCompletionEvent(AsyncHttpAppender.BatchCompletionEvent event) {
            synchronized (this) {
                lastBatchCompletionEvents.addLast(event);

                if (lastBatchCompletionEvents.size() > 100) {
                    lastBatchCompletionEvents.removeFirst();
                }
            }

            AsyncHttpAppender.logBatchCompletionEvent(LOG, ForkJoinPool.commonPool(), event);
        }

        List<AsyncHttpAppender.BatchCompletionEvent> getLastBatchCompletionEvents() {
            synchronized (this) {
                return List.copyOf(lastBatchCompletionEvents);
            }
        }
    }

    @Test
    void shouldLoadBasicConfig() {
        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.basicConfig.xml")) {
            var appender = (AsyncHttpAppender) context.getConfiguration().getAppender("AsyncHttp");

            assertThat(appender).isNotNull();
            assertThat(appender.getLayout()).isInstanceOf(PatternLayout.class);

            assertThat(context.getConfiguration().getRootLogger().getAppenderRefs())
                    .map(AppenderRef::getRef)
                    .containsExactlyInAnyOrder("AsyncHttp", "Console");

            assertThat(Arrays.asList(appender.getPropertyArray()))
                    .hasSize(2)
                    .map(p -> Pair.of(p.getName(), p.getValue()))
                    .containsExactlyInAnyOrder(
                            Pair.of("Authorization", "Api-Token secret"),
                            Pair.of("Content-Type", "application/jsonl"));

            assertThat(appender.url()).asString().contains("y.xz");
            assertThat(appender.lingerMs()).isEqualTo(5000);
            assertThat(appender.maxBatchBytes()).isEqualTo(250_000);

            assertThat(appender.connectTimeoutMs()).isEqualTo(10_000);
            assertThat(appender.readTimeoutMs()).isEqualTo(10_000);
            assertThat(appender.maxConcurrentRequests()).isEqualTo(5);
            assertThat(appender.contentEncoding()).isEqualTo(ContentEncoding.IDENTITY);
            assertThat(appender.batchSeparatorInsertionStrategy()).isEqualTo(BatchSeparatorInsertionStrategy.IF_MISSING);
            assertThat(appender.retryOnIoError()).isTrue();

            assertThat(appender.httpSuccessCodes()).containsExactly(200, 202, 204);
            assertThat(appender.httpRetryCodes()).containsExactly(429, 500, 502, 503, 504);
        }
    }

    @Test
    void shouldLoadFullConfig() {
        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.fullConfig.xml")) {
            var appender = (AsyncHttpAppender) context.getConfiguration().getAppender("AsyncHttp");

            assertThat(context.getConfiguration().getRootLogger().getAppenderRefs())
                    .map(AppenderRef::getRef)
                    .containsExactlyInAnyOrder("AsyncHttp", "Console");

            assertThat(Arrays.asList(appender.getPropertyArray()))
                    .hasSize(2)
                    .map(p -> Pair.of(p.getName(), p.getValue()))
                    .containsExactlyInAnyOrder(
                            Pair.of("Authorization", "Api-Token secret"),
                            Pair.of("Content-Type", "application/jsonl"));

            assertThat(appender.url()).asString().contains("y.xz");
            assertThat(appender.maxBatchBytes()).isEqualTo(1234);
            assertThat(appender.lingerMs()).isEqualTo(567);
            assertThat(appender.maxBatchLogEvents()).isEqualTo(78);
            assertThat(appender.batchPrefix()).isEqualTo("start");
            assertThat(appender.batchSeparator()).isEqualTo("sep");
            assertThat(appender.batchSuffix()).isEqualTo("end");
            assertThat(appender.ignoreExceptions()).isFalse();
            assertThat(appender.connectTimeoutMs()).isEqualTo(888);
            assertThat(appender.readTimeoutMs()).isEqualTo(999);
            assertThat(appender.maxConcurrentRequests()).isEqualTo(17);
            assertThat(appender.method()).isEqualTo(HttpMethod.PUT);
            assertThat(appender.batchSeparatorInsertionStrategy()).isEqualTo(BatchSeparatorInsertionStrategy.ALWAYS);
            assertThat(appender.contentEncoding()).isEqualTo(ContentEncoding.GZIP);
            assertThat(appender.retryOnIoError()).isFalse();
            assertThat(appender.retries()).isEqualTo(11);
            assertThat(appender.httpClientSslConfigSupplier()).isEqualTo("com.github.mlangc.more.log4j2.appenders.AsyncHttpAppenderTest$SslConfigSupplier");
            assertThat(appender.httpSuccessCodes()).containsExactly(200, 201, 202, 203);
            assertThat(appender.httpRetryCodes()).containsExactly(500, 502);
            assertThat(appender.shutdownTimeoutMs()).isEqualTo(323);
            assertThat(appender.maxBlockOnOverflowMs()).isEqualTo(456);

            assertThat(appender.overflowAppenderRef())
                    .isNotNull()
                    .satisfies(appenderRef -> {
                        assertThat(appenderRef.ref()).isEqualTo("Console");
                        assertThat(appenderRef.filter()).isInstanceOf(BurstFilter.class);
                    });
        }
    }

    @Test
    void shouldPostSingleLogLine() {
        wireMockExt.stubFor(post(wireMockPath)
                .withRequestBody(containing("Hello world"))
                .willReturn(ok()));

        try (LoggerContext context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.minimalWiremockHttpConfig.xml")) {
            context.getLogger(getClass()).info("Hello world");
        }

        wireMockExt.verify(1, postRequestedFor(urlEqualTo(wireMockPath)));
    }

    @Test
    void shouldPostAllLogLines() {
        wireMockExt.stubFor(post(wireMockPath)
                .withRequestBody(containing("Hello world"))
                .willReturn(ok()));

        final int numLogs = 10_000;
        try (LoggerContext context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.minimalWiremockHttpConfig.xml")) {
            for (int i = 0; i < numLogs; i++) {
                context.getLogger(getClass()).info("Hello world - {}", i);
            }
        }

        List<String> postedLogLines = wireMockExt.findAll(postRequestedFor(urlEqualTo(wireMockPath))).stream()
                .flatMap(AsyncHttpAppenderTest::requestBodyToLinesStream)
                .collect(Collectors.toList());

        assertThat(postedLogLines)
                .filteredOn(s -> s.contains("Hello world"))
                .hasSize(numLogs)
                .doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @EnumSource
    void shouldRespectHttpMethod(HttpMethod method) {
        wireMockExt.stubFor(post(wireMockPath).willReturn(method == HttpMethod.POST ? ok() : forbidden()));
        wireMockExt.stubFor(WireMock.put(wireMockPath).willReturn(method == HttpMethod.PUT ? ok() : forbidden()));

        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        BuiltConfiguration config = configBuilder.add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("method", method.name())
                        .addAttribute("batchSeparator", "\n")
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")))
                .build(false);

        try (LoggerContext context = TestHelpers.loggerContextFromConfig(config)) {
            context.getLogger(getClass()).info("test");
        }

        wireMockExt.verify(1, requestedFor(method.name(), urlEqualTo(wireMockPath)));
    }

    static class StressTestCase {
        boolean https;
        int parallelism = 1;
        int medianServerResponseMs = 1;
        int medianServerResponseSigma = 1;
        double serverFailureRate;
        int retries = 5;
        int lingerMs = 100;
        int maxBatchLogEvents = 5000;
        int maxBatchBytes = 250_000;
        boolean mustEventuallySucceed = true;
        int maxBlockOnOverflowMs;
        boolean mustNotDropLogs;

        @Override
        public String toString() {
            return ReflectionToStringBuilder.toString(this, ToStringStyle.SHORT_PREFIX_STYLE);
        }

        static StressTestCase tweaked(Consumer<StressTestCase> tweaks) {
            StressTestCase testCase = new StressTestCase();
            tweaks.accept(testCase);
            return testCase;
        }
    }

    static Stream<StressTestCase> stressTestCases() {
        return Stream.of(
                new StressTestCase(),
                StressTestCase.tweaked(t -> t.https = true),
                StressTestCase.tweaked(t -> t.parallelism = 4),
                StressTestCase.tweaked(t -> t.maxBatchLogEvents = 50),
                StressTestCase.tweaked(t -> t.retries = 1),
                StressTestCase.tweaked(t -> t.https = true),
                StressTestCase.tweaked(t -> {
                    t.serverFailureRate = 0.5;
                    t.parallelism = 1;
                    t.mustEventuallySucceed = false;
                }),
                StressTestCase.tweaked(t -> {
                    t.serverFailureRate = 0.25;
                    t.parallelism = 3;
                    t.mustEventuallySucceed = false;
                }),
                StressTestCase.tweaked(t -> t.parallelism = 2),
                StressTestCase.tweaked(t -> {
                    t.https = true;
                    t.parallelism = 3;
                }),
                StressTestCase.tweaked(t -> t.parallelism = 4),
                StressTestCase.tweaked(t -> {
                    t.parallelism = 1;
                    t.serverFailureRate = 0.4;
                    t.retries = 0;
                    t.maxBatchLogEvents = 50;
                    t.mustEventuallySucceed = false;
                }),
                StressTestCase.tweaked(t -> {
                    t.parallelism = 1;
                    t.serverFailureRate = 0.05;
                    t.retries = 10;
                    t.maxBatchLogEvents = 100;
                }),
                StressTestCase.tweaked(t -> {
                    t.parallelism = 1;
                    t.serverFailureRate = 0.2;
                    t.retries = 20;
                    t.maxBatchLogEvents = 100;
                }),
                StressTestCase.tweaked(t -> {
                    t.parallelism = 1;
                    t.serverFailureRate = 0.1;
                    t.retries = 10;
                    t.maxBatchLogEvents = 100;
                }),
                StressTestCase.tweaked(t -> {
                    t.parallelism = 4;
                    t.serverFailureRate = 0.1;
                    t.retries = 10;
                    t.maxBatchLogEvents = 100;
                }),
                StressTestCase.tweaked(t -> {
                    t.medianServerResponseMs = 10;
                    t.maxBlockOnOverflowMs = 1000;
                    t.maxBatchLogEvents = 50;
                    t.mustEventuallySucceed = true;
                    t.mustNotDropLogs = true;
                }),
                StressTestCase.tweaked(t -> {
                    t.medianServerResponseMs = 10;
                    t.maxBlockOnOverflowMs = 2000;
                    t.maxBatchLogEvents = 100;
                    t.mustEventuallySucceed = true;
                    t.mustNotDropLogs = true;
                })
        );
    }

    @ParameterizedTest
    @MethodSource("stressTestCases")
    void shouldWorkReliablyUnderStress(StressTestCase testCase) {
        var url = testCase.https ? wireMockHttpsUrl : wireMockHttpUrl;

        Runnable configureOkResponse = () ->
                wireMockExt.stubFor(post(wireMockPath).willReturn(ok().withLogNormalRandomDelay(testCase.medianServerResponseMs, testCase.medianServerResponseSigma)));

        if (testCase.serverFailureRate <= 0) {
            configureOkResponse.run();
        } else if (testCase.serverFailureRate > 0) {
            var random = new Random(313);

            Runnable configureFailureResponse = () -> {
                wireMockExt.stubFor(post(wireMockPath).willReturn(
                        (switch (random.nextInt(3)) {
                            case 0 -> serverError();
                            case 1 -> serviceUnavailable();
                            default -> aResponse().withStatus(507).withFault(Fault.values()[random.nextInt(Fault.values().length)]);
                        }).withLogNormalRandomDelay(testCase.medianServerResponseMs, testCase.medianServerResponseSigma)));
            };

            Runnable configureRandomResponse;
            if (testCase.serverFailureRate >= 1) {
                configureRandomResponse = configureFailureResponse;
            } else {
                configureRandomResponse = () -> {
                    if (random.nextDouble() < testCase.serverFailureRate) {
                        configureFailureResponse.run();
                    } else {
                        configureOkResponse.run();
                    }
                };
            }

            configureRandomResponse.run();
            schedulesToCancelAfterTest.add(executor.scheduleAtFixedRate(configureRandomResponse, 1, 1, TimeUnit.MILLISECONDS));
        }

        String configName = "CStress@" + testCase;
        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder.setConfigurationName(configName)
                .setStatusLevel(Level.WARN)
                .add(configBuilder.newAppender("Count", "CountingAppender"))
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", url)
                        .addAttribute("lingerMs", testCase.lingerMs)
                        .addAttribute("maxBatchBytes", testCase.maxBatchBytes)
                        .addAttribute("maxBatchLogEvents", testCase.maxBatchLogEvents)
                        .addAttribute("maxBlockOnOverflowMs", testCase.maxBlockOnOverflowMs)
                        .addAttribute("retries", testCase.retries)
                        .addAttribute("httpClientSslConfigSupplier", AsyncHttpAppenderTest.class.getCanonicalName() + "$" + SslConfigSupplier.class.getSimpleName())
                        .addAttribute("batchCompletionListener", "com.github.mlangc.more.log4j2.appenders.AsyncHttpAppenderTest$TestBatchCompletionListener")
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg"))
                        .addComponent(configBuilder.newComponent("OverflowAppenderRef").addAttribute("ref", "Count").addAttribute("level", "ALL")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        int logsPerThread = 100_000;
        LoggerContext context = TestHelpers.loggerContextFromConfig(configBuilder);
        TestBatchCompletionListener batchCompletionListener;
        CountingAppender countingAppender;
        try {
            Logger logger = context.getLogger(getClass());
            batchCompletionListener = (TestBatchCompletionListener) getAsyncHttpAppender(context).batchCompletionListener();
            countingAppender = context.getConfiguration().getAppender("Count");

            IntFunction<Runnable> logBurstJob = id -> () -> {
                for (int i = 0; i < logsPerThread; i++) {
                    logger.info("i_{}:{}", id, i);
                }
            };

            List<CompletableFuture<Void>> futures = IntStream.range(0, testCase.parallelism)
                    .mapToObj(id -> CompletableFuture.runAsync(logBurstJob.apply(id)))
                    .toList();

            for (CompletableFuture<Void> future : futures) {
                assertThat(future).succeedsWithin(10, TimeUnit.SECONDS);
            }
        } finally {
            assertThat(context.stop(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(batchCompletionListener.getLastBatchCompletionEvents())
                .isNotEmpty()
                .allSatisfy(evt -> {
                    assertThat(evt.stats().bufferedBatches()).isLessThanOrEqualTo(evt.source().maxBatchBufferBatches());
                    assertThat(evt.stats().bufferedBatchBytes()).isLessThanOrEqualTo(evt.source().maxBatchBufferBytes());
                    assertThat(evt.stats().batchBytesEffective()).isLessThanOrEqualTo(evt.source().maxBatchBytes());
                    assertThat(evt.stats().batchBytesUncompressed()).isLessThanOrEqualTo(evt.source().maxBatchBytes());
                    assertThat((long) evt.stats().batchBytesEffective()).isLessThanOrEqualTo(evt.stats().batchBytesUncompressed());
                    assertThat(evt.stats().batchLogEvents()).isLessThanOrEqualTo(evt.source().maxBatchLogEvents());
                });

        var receivedLinesPerStatusCode = collectReceivedLinesPerStatusCode(wireMockPath);
        assertThat(receivedLinesPerStatusCode.get(200))
                .as(() -> wireMockExt.getAllServeEvents().toString())
                .isNotEmpty()
                .allSatisfy((line, count) -> assertThat(count).isOne());

        var postedLinesTotal = new HashMap<String, Integer>();
        receivedLinesPerStatusCode.values().stream()
                .flatMap(m -> m.entrySet().stream())
                .forEach(e -> postedLinesTotal.merge(e.getKey(), e.getValue(), Integer::sum));

        Pattern logLineRegex = Pattern.compile("i_(\\d+):(\\d+)");
        Supplier<String> postedLogLinesString = () -> {
            record LogLine(int t, int i) { }

            var missingLogLines = IntStream.range(0, testCase.parallelism).boxed()
                    .flatMap(t -> IntStream.range(0, logsPerThread).mapToObj(i -> new LogLine(t, i)))
                    .collect(Collectors.toCollection(HashSet::new));

            postedLinesTotal.keySet().forEach(line -> {
                var matcher = logLineRegex.matcher(line);
                if (matcher.matches()) {
                    missingLogLines.remove(new LogLine(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
                }
            });

            var numMissingLogLinesPerThread = new HashMap<Integer, Integer>();
            missingLogLines.forEach(logLine -> numMissingLogLinesPerThread.merge(logLine.t, 1, Integer::sum));

            var firstMissingLogLinePerThread = new HashMap<Integer, LogLine>();
            missingLogLines.forEach(logLine ->
                    firstMissingLogLinePerThread.merge(logLine.t, logLine, (oldLine, newLine) -> oldLine.i < newLine.i ? oldLine : newLine));

            return String.format("firstMissingLogLinesPerThread=%s, numMissingLogLinesPerThread=%s, postedLogLinesTotal=%s",
                    firstMissingLogLinePerThread, numMissingLogLinesPerThread, postedLinesTotal);
        };

        var droppedLogsTotal = Math.toIntExact(countingAppender.currentCount());
        if (testCase.mustNotDropLogs) {
            assertThat(droppedLogsTotal).isZero();
        }

        var expectedLinesTotal = logsPerThread * testCase.parallelism - droppedLogsTotal;
        assertThat(postedLinesTotal)
                .as(postedLogLinesString)
                .hasSize(expectedLinesTotal)
                .allSatisfy((line, ignore) -> assertThat(line).matches(logLineRegex));

        if (testCase.mustEventuallySucceed) {
            assertThat(receivedLinesPerStatusCode.get(200))
                    .allSatisfy((k, v) -> assertThat(v).isOne())
                    .hasSize(expectedLinesTotal);
        }

        assertThat(batchCompletionListener.getLastBatchCompletionEvents())
                .isNotEmpty()
                .allSatisfy(evt -> {
                    assertThat(evt.stats().tries()).isPositive();
                    assertThat((long) evt.stats().batchBytesEffective()).isLessThanOrEqualTo(evt.stats().batchBytesUncompressed());
                    assertThat(evt.stats().totalNanos()).isPositive();
                    assertThat(evt.stats().requestNanos()).isPositive();
                    assertThat(evt.stats().backoffNanos()).isNotNegative();
                    assertThat(evt.stats().totalNanos()).isGreaterThanOrEqualTo(evt.stats().backoffNanos() + evt.stats().requestNanos());
                });
    }

    static Map<Integer, Map<String, Integer>> collectReceivedLinesPerStatusCode(String path) {
        return collectReceivedLinesPerStatusCode(urlEqualTo(path));
    }

    static Map<Integer, Map<String, Integer>> collectReceivedLinesPerStatusCode(UrlPattern urlPattern) {
        var res = new HashMap<Integer, Map<String, Integer>>();

        for (ServeEvent event : wireMockExt.getAllServeEvents()) {
            LoggedRequest request = event.getRequest();
            LoggedResponse response = event.getResponse();

            if (urlPattern.match(request.getUrl()).isExactMatch()) {
                var linesCountsPerStatusCode = res.computeIfAbsent(response.getStatus(), ignore -> new HashMap<>());
                requestBodyToLinesStream(request).forEach(line -> linesCountsPerStatusCode.merge(line, 1, Integer::sum));
            }
        }

        return res;
    }

    static List<String> requestBodyToLines(LoggedRequest request) {
        return requestBodyToLinesStream(request).toList();
    }

    static Stream<String> requestBodyToLinesStream(LoggedRequest request) {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(request.getBody()), StandardCharsets.UTF_8)).lines();
    }

    @Test
    void appenderShouldNotBlockIfHttpEndpointIsExtremelySlow() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok().withLogNormalRandomDelay(1000, 2)));

        String configName = AsyncHttpAppenderTest.class.getSimpleName();
        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder.setConfigurationName(configName)
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("maxConcurrentRequests", 2)
                        .addAttribute("maxBatchLogEvents", 1)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
        Configuration config = configBuilder.build(false);

        var context = TestHelpers.loggerContextFromConfig(config);
        try {
            var logger = context.getLogger(getClass());
            assertThat((Appender) context.getConfiguration().getAppender("AsyncHttp")).isNotNull();

            var logManyLinesJob = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 5000; i++) {
                    logger.info("test");
                }
            });

            assertThat(logManyLinesJob).succeedsWithin(1, TimeUnit.SECONDS);
        } finally {
            context.stop(2, TimeUnit.SECONDS);
        }

        wireMockExt.verify(moreThan(1), postRequestedFor(urlEqualTo(wireMockPath)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 11, 23})
    void shouldRespectMaxBatchLogEvents(int maxBatchLogEvents) {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("maxBatchLogEvents", maxBatchLogEvents)
                        .addAttribute("maxBatchBytes", Integer.MAX_VALUE)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
        var config = configBuilder.build(false);

        var expectedBatches = 5;
        try (var context = TestHelpers.loggerContextFromConfig(config)) {
            var log = context.getLogger(getClass());

            for (int i = 0; i < expectedBatches * maxBatchLogEvents; i++) {
                log.info("testing");
            }
        }

        assertThat(wireMockExt.findAll(postRequestedFor(urlEqualTo(wireMockPath))))
                .hasSize(expectedBatches)
                .allSatisfy(request -> assertThat(requestBodyToLines(request))
                        .hasSize(maxBatchLogEvents)
                        .allSatisfy(line -> assertThat(line).isEqualTo("testing")));
    }

    @Test
    void shouldSendEverythingTwiceIfEndpointAlwaysFailsTheFirstTime() {
        wireMockExt.stubFor(post(wireMockPath)
                .inScenario("flaky service")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("working")
                .willReturn(aResponse().withStatus(500)));

        wireMockExt.stubFor(post(wireMockPath)
                .inScenario("flaky service")
                .whenScenarioStateIs("working")
                .willSetStateTo(Scenario.STARTED)
                .willReturn(ok()));

        String configName = AsyncHttpAppenderTest.class.getSimpleName();
        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder.setConfigurationName(configName)
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("lingerMs", 10)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
        Configuration config = configBuilder.build(false);

        final int numLogs = 100;
        try (var context = TestHelpers.loggerContextFromConfig(config)) {
            var log = context.getLogger(getClass());

            for (int i = 0; i < numLogs; i++) {
                log.info("test - {}", i);
            }
        }

        var lineStats = collectReceivedLinesPerStatusCode(wireMockPath);
        assertThat(lineStats.get(200)).hasSize(numLogs);
        assertThat(lineStats.get(500)).isNotEmpty();
    }

    @Test
    void shouldRespectGzipContentEncoding() {
        wireMockExt.stubFor(post(wireMockPath)
                .withHeader("Content-Encoding", equalTo("gzip"))
                .willReturn(ok()));

        String configName = AsyncHttpAppenderTest.class.getSimpleName();
        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder.setConfigurationName(configName)
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("contentEncoding", "gzip")
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
        Configuration config = configBuilder.build(false);

        try (var context = TestHelpers.loggerContextFromConfig(config)) {
            context.getLogger(getClass()).info("test");
        }

        var lineStats = collectReceivedLinesPerStatusCode(wireMockPath);
        assertThat(lineStats.getOrDefault(200, Map.of())).containsExactly(Map.entry("test", 1));
    }

    @Test
    void shouldRespectAppenderStageFilters() {
        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.configWithAppenderFilter.xml")) {
            shouldRespectAppenderFilters(context);
        }
    }

    @Test
    void shouldRespectAppenderRefStageFilters() {
        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.configWithAppenderRefFilter.xml")) {
            shouldRespectAppenderFilters(context);
        }
    }

    @Test
    void shouldRespectSeparatorInsertionStrategyIfAlways() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.configWithSeparatorInsertionAlways.xml")) {
            var log = context.getLogger(getClass());
            log.info("a");
            log.info("b");
            log.info("c");
            log.info("#d");
            log.info("#e");
            log.info("#");
        }

        assertThat(wireMockExt.findAll(postRequestedFor(urlEqualTo(wireMockPath))))
                .hasSize(1)
                .map(LoggedRequest::getBodyAsString)
                .first()
                .isEqualTo("(a#b#c##d##e##)");
    }

    @Test
    void shouldRespectSeparatorInsertionStrategyIfMissing() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.configWithSeparatorInsertionIfMissing.xml")) {
            var log = context.getLogger(getClass());
            log.info("#a");
            log.info("b#");
            log.info("c");
            log.info("#d");
            log.info("#e");
            log.info("#");
        }

        assertThat(wireMockExt.findAll(postRequestedFor(urlEqualTo(wireMockPath))))
                .hasSize(1)
                .map(LoggedRequest::getBodyAsString)
                .first()
                .isEqualTo("(#a#b#c#d#e#)");
    }

    @Test
    void shouldRespectBatchPrefixAndSuffix() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.configWithPrefixAndSuffix.xml")) {
            var log = context.getLogger(getClass());
            log.info("hello");
        }

        assertThat(wireMockExt.findAll(postRequestedFor(urlEqualTo(wireMockPath))))
                .hasSize(1)
                .map(LoggedRequest::getBodyAsString)
                .first()
                .isEqualTo("start:hello:end");
    }

    @Test
    void shouldWorkWithMultipleAppendersWithSimilarConfigurations() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

        var marker1 = MarkerManager.getMarker("marker1");
        var marker2 = MarkerManager.getMarker("marker2");
        var marker3 = MarkerManager.getMarker("marker3");
        var marker4 = MarkerManager.getMarker("marker4");

        var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.configWithMultipleAppenders.xml");
        try {
            var log = context.getLogger(getClass());
            log.info(marker1, "test");
            log.info(marker2, "test");
            log.info(marker3, "test");
            log.info(marker4, "test");
            log.info("test");
        } finally {
            assertThat(context.stop(10, TimeUnit.SECONDS)).isTrue();
        }

        Map<String, Integer> lines = collectReceivedLinesPerStatusCode(wireMockPath).get(200);
        assertThat(lines.entrySet()).containsExactlyInAnyOrder(
                Map.entry("(1) - test", 1),
                Map.entry("(2) - test", 1),
                Map.entry("(3) - test", 1),
                Map.entry("(*) - test", 2));
    }

    @Test
    void shouldRespectLingerMs() throws InterruptedException {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.with1sLingerMs.xml")) {
            var log = context.getLogger(getClass());
            log.info("tada");

            Thread.sleep(750);
            wireMockExt.verify(0, postRequestedFor(urlEqualTo(wireMockPath)));

            Awaitility.await().atMost(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> wireMockExt.verify(1, postRequestedFor(urlEqualTo(wireMockPath))));
        }
    }

    @Test
    void shouldRespectPropertiesAndSendThemAsHttpHeaders() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));
        System.setProperty("testPropertyName", "dynamo");
        System.setProperty("testPropertyValue", "kyiv");

        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.withMultipleProperties.xml")) {
            var log = context.getLogger(getClass());
            log.info("xyz");
        }

        var javaLookup = new JavaLookup();
        wireMockExt.verify(1, postRequestedFor(urlEqualTo(wireMockPath))
                .withHeader("x", equalTo("y"))
                .withHeader("java.version", equalTo(javaLookup.lookup(null, "version")))
                .withHeader("dynamo", equalTo("kyiv"))
                .withHeader("mixed-name-dynamo-name-mixed", equalTo("mixed-value-kyiv-value-mixed")));
    }

    @Test
    void shouldNotRetryOn400InDefaultConfig() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(badRequest()));

        var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.minimalWiremockHttpConfig.xml");
        try {
            var log = context.getLogger(getClass());
            log.info("test");
        } finally {
            assertThat(context.stop(1, TimeUnit.SECONDS)).isTrue();
        }

        wireMockExt.verify(1, postRequestedFor(urlEqualTo(wireMockPath)));
    }

    @Test
    void shouldRespectHttpSuccessAndRetryCodesEvenIfTheyAreWeired() throws InterruptedException {
        wireMockExt.stubFor(post("/logs/love500").willReturn(serverError()));
        wireMockExt.stubFor(post("/logs/hate500").willReturn(serverError()));

        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.configWeiredHttpCodes.xml")) {
            var love500Marker = MarkerManager.getMarker("love500");
            var log = context.getLogger(getClass());

            log.info(love500Marker, "500 reasons for love");
            log.info("500 reasons for hate");

            Thread.sleep(5); // <-- wait shortly for linger & retries
        }

        assertThat(wireMockExt.findAll(postRequestedFor(urlEqualTo("/logs/love500"))))
                .hasSize(1)
                .map(LoggedRequest::getBodyAsString)
                .first()
                .isEqualTo("500 reasons for love");

        assertThat(wireMockExt.findAll(postRequestedFor(urlEqualTo("/logs/hate500"))))
                .hasSize(2)
                .map(LoggedRequest::getBodyAsString)
                .allSatisfy(s -> assertThat(s).isEqualTo("500 reasons for hate"));
    }

    @Test
    void shouldNotAllowHttpSuccessAndRetryCodesToIntersect() {
        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", "http://was.ich.seh.com")
                        .addAttribute("httpSuccessCodes", "200, 202, 500")
                        .addAttribute("httpRetryCodes", "500, 503")
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
        var config = configBuilder.build(false);

        try (var context = TestHelpers.loggerContextFromConfig(config)) {
            assertThat((Appender) context.getConfiguration().getAppender("AsyncHttp")).isNull();
        }
    }

    @Test
    void shouldNotAllowHttpBrokenHttpStatusCodes() {
        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", "http://was.ich.seh.com")
                        .addAttribute("httpSuccessCodes", "200, 202, -1")
                        .addAttribute("httpRetryCodes", "500, 503, 999")
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
        var config = configBuilder.build(false);

        try (var context = TestHelpers.loggerContextFromConfig(config)) {
            assertThat((Appender) context.getConfiguration().getAppender("AsyncHttp")).isNull();
        }
    }

    @Test
    void shouldNotAllowEmptyHttpSuccessCodes() {
        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", "http://was.ich.seh.com")
                        .addAttribute("httpSuccessCodes", "")
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
        var config = configBuilder.build(false);

        try (var context = TestHelpers.loggerContextFromConfig(config)) {
            assertThat((Appender) context.getConfiguration().getAppender("AsyncHttp")).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldNotAllowMaxBatchBufferBytesZeroOrBelow(int maxBatchBufferBytes) {
        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", "http://was.ich.seh.com")
                        .addAttribute("maxBatchBufferBytes", maxBatchBufferBytes)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
        var config = configBuilder.build(false);

        try (var context = TestHelpers.loggerContextFromConfig(config)) {
            assertThat((Appender) context.getConfiguration().getAppender("AsyncHttp")).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldNotAllowMaxBatchLogEventsSmaller1(int maxBatchLogEvents) {
        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", "http://was.ich.seh.com")
                        .addAttribute("maxBatchLogEvents", maxBatchLogEvents)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
        var config = configBuilder.build(false);

        try (var context = TestHelpers.loggerContextFromConfig(config)) {
            assertThat((Appender) context.getConfiguration().getAppender("AsyncHttp")).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldNotAllowLingerMsSmaller1(int lingerMs) {
        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", "http://was.ich.seh.com")
                        .addAttribute("lingerMs", lingerMs)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
        var config = configBuilder.build(false);

        try (var context = TestHelpers.loggerContextFromConfig(config)) {
            assertThat((Appender) context.getConfiguration().getAppender("AsyncHttp")).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldNotAllowMaxInFlightSmaller1(int maxConcurrentRequests) {
        ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", "http://was.ich.seh.com")
                        .addAttribute("maxConcurrentRequests", maxConcurrentRequests)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
        var config = configBuilder.build(false);

        try (var context = TestHelpers.loggerContextFromConfig(config)) {
            assertThat((Appender) context.getConfiguration().getAppender("AsyncHttp")).isNull();
        }
    }

    @Test
    void shouldWorkWithBatchSize0() {
        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.configWithMaxBatchBytes0.xml")) {
            shouldPush3shortLogs(context);
        }
    }

    @Test
    void shouldWorkWithBatchSize1() {
        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.configWithMaxBatchBytes1.xml")) {
            shouldPush3shortLogs(context);
        }
    }

    @Test
    void shouldNotDropBatchesIfMaxBlockOnOverflowMsIsHuge() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok().withFixedDelay(1)));

        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("Count", "CountingAppender"))
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("maxBlockOnOverflowMs", Integer.MAX_VALUE)
                        .addAttribute("maxBatchBufferBytes", 1000)
                        .addAttribute("maxBatchLogEvents", 1)
                        .addAttribute("maxConcurrentRequests", 1)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg"))
                        .addComponent(configBuilder.newComponent("OverflowAppenderRef").addAttribute("ref", "Count")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        var longStr = "x".repeat(100);
        var logLines = 100;

        CountingAppender countingAppender;
        try (var context = TestHelpers.loggerContextFromConfig(configBuilder)) {
            var log = context.getLogger(getClass());
            countingAppender = context.getConfiguration().getAppender("Count");

            for (int i = 0; i < logLines; i++) {
                log.info("{} - {}", longStr, i);
            }
        }

        assertThat(countingAppender.currentCount()).isZero();
        wireMockExt.verify(logLines, postRequestedFor(urlEqualTo(wireMockPath)));
    }

    private void shouldPush3shortLogs(LoggerContext context) {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

        var log = context.getLogger(getClass());
        log.warn("tada");
        log.warn("tödö");
        log.warn("trörö");
        context.close();

        List<LoggedRequest> postRequests = wireMockExt.findAll(postRequestedFor(urlEqualTo(wireMockPath)));
        assertThat(postRequests)
                .as(postRequests::toString)
                .hasSize(3)
                .map(LoggedRequest::getBodyAsString)
                .containsExactlyInAnyOrder("tada", "tödö", "trörö");
    }

    @Test
    void shouldNotDropLogLineThatExceedsBatchBufferSize() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.configWithMaxBatchBytes1.xml")) {
            var log = context.getLogger(getClass());
            log.info("loooooooooooooooooooooooooooooooooooooooooooooooooooooong");
        }

        List<LoggedRequest> postRequests = wireMockExt.findAll(postRequestedFor(urlEqualTo(wireMockPath)));
        assertThat(postRequests)
                .as(postRequests::toString)
                .hasSize(1)
                .map(LoggedRequest::getBodyAsString)
                .containsExactly("loooooooooooooooooooooooooooooooooooooooooooooooooooooong");
    }

    @Test
    void shouldWorkWithVeryLongBatchSeparator() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.configWithVeryLongSeparator.xml")) {
            var log = context.getLogger(getClass());
            log.info("###");
            log.info("###");
            log.info("###");
        }

        wireMockExt.verify(1, postRequestedFor(urlEqualTo(wireMockPath)).withRequestBody(equalTo("###l012345678901234567890123456789g###l012345678901234567890123456789g###")));
    }

    @Test
    void droppedBatchesShouldBeConsistentWithDataSentToBackend() {
        wireMockExt.stubFor(post(wireMockPath).willReturn(ok().withFixedDelay(250)));

        final var numLongMessages = 3;
        String longMessage;
        CountingAppender overflowAppender;
        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.withSmallBatchAndBatchBufferSize.xml")) {
            var batchBufferBytes = ((AsyncHttpAppender) context.getConfiguration().getAppender("AsyncHttp")).maxBatchBufferBytes();
            var log = context.getLogger(getClass());
            longMessage = "x".repeat(batchBufferBytes + 1);

            for (int i = 0; i < numLongMessages; i++) {
                log.info(longMessage);
            }

            overflowAppender = context.getConfiguration().getAppender("Count");
        }

        var batchesDropped = overflowAppender.currentCount();
        assertThat(batchesDropped).isPositive();
        wireMockExt.verify(Math.toIntExact(numLongMessages - batchesDropped), postRequestedFor(urlEqualTo(wireMockPath)).withRequestBody(equalTo(longMessage)));
    }

    @Test
    void shouldProperlyReportBatchCompletionEvents() {
        var scenarioName = "okFailFailScenario";

        wireMockExt.stubFor(post(wireMockPath).inScenario(scenarioName)
                .willReturn(ok().withStatusMessage("OK"))
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("fail1"));

        wireMockExt.stubFor(post(wireMockPath).inScenario(scenarioName)
                .willReturn(aResponse().withStatus(507).withFault(Fault.MALFORMED_RESPONSE_CHUNK))
                .whenScenarioStateIs("fail1")
                .willSetStateTo("fail2"));

        wireMockExt.stubFor(post(wireMockPath).inScenario(scenarioName)
                .willReturn(badRequest().withStatusMessage("Bad Request"))
                .whenScenarioStateIs("fail2")
                .willSetStateTo(Scenario.STARTED));

        var longMessage = "x".repeat(100);
        try (var context = TestHelpers.loggerContextFromTestResource("AsyncHttpAppenderTest.withSettingsToTestCompletionListener.xml")) {
            var appender = (AsyncHttpAppender) context.getConfiguration().getAppender("AsyncHttp");
            var batchCompletionListener = (TestBatchCompletionListener) appender.batchCompletionListener();
            assertThat(appender.retries()).isZero();
            var log = context.getLogger(getClass());

            log.info(longMessage);

            Awaitility.await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(batchCompletionListener.getLastBatchCompletionEvents())
                        .hasSize(1)
                        .allSatisfy(evt -> {
                            assertThat(evt.type()).isInstanceOfSatisfying(AsyncHttpAppender.BatchDeliveredSuccess.class, deliveredSuccess ->
                                    assertThat(deliveredSuccess.httpStatus().code()).isEqualTo(200));

                            assertThat(evt.stats().tries()).isOne();
                            assertThat(evt.stats().batchBytesUncompressed())
                                    .isEqualTo(longMessage.length())
                                    .isGreaterThan(evt.stats().batchBytesEffective());

                            assertThat(evt.stats().batchLogEvents()).isOne();
                            assertThat(evt.stats().bufferedBatches()).isZero();
                        });
            });

        log.info(longMessage);

        Awaitility.await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(batchCompletionListener.getLastBatchCompletionEvents())
                    .hasSize(2)
                    .last().satisfies(evt -> {
                        assertThat(evt.type()).isInstanceOfSatisfying(AsyncHttpAppender.BatchDeliveryFailed.class, deliveryFailed ->
                                assertThat(deliveryFailed.exception()).isNotNull());

                        assertThat(evt.stats().tries()).isOne();
                    });
        });

        log.info(longMessage);

        Awaitility.await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(batchCompletionListener.getLastBatchCompletionEvents())
                    .hasSize(3)
                    .allSatisfy(evt -> assertThat(evt.source()).isSameAs(appender))
                    .last().satisfies(evt -> {
                        assertThat(evt.type()).isInstanceOfSatisfying(AsyncHttpAppender.BatchDeliveredError.class, deliveredError ->
                                assertThat(deliveredError.httpStatus().code()).isEqualTo(400));

                        assertThat(evt.stats().tries()).isOne();
                    });
        });
    }
}

@ParameterizedTest
@ValueSource(ints = {1, 2, 5, 50})
void shouldRespectMaxBatchBufferBatches(int maxBatchBufferBatches) {
    wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

    var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
    configBuilder = configBuilder
            .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                    .addAttribute("url", wireMockHttpUrl)
                    .addAttribute("maxBlockOnOverflowMs", Integer.MAX_VALUE)
                    .addAttribute("maxBatchBufferBatches", maxBatchBufferBatches)
                    .addAttribute("maxBatchLogEvents", 1)
                    .addAttribute("batchCompletionListener", "com.github.mlangc.more.log4j2.appenders.AsyncHttpAppenderTest$TestBatchCompletionListener")
                    .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
            .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

    assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
    var config = configBuilder.build(false);

    var numLogLines = maxBatchBufferBatches + 10;
    TestBatchCompletionListener completionListener;
    try (var context = TestHelpers.loggerContextFromConfig(config)) {
        var appender = getAsyncHttpAppender(context);
        completionListener = (TestBatchCompletionListener) appender.batchCompletionListener();

        var log = context.getLogger(getClass());
        for (int i = 0; i < numLogLines; i++) {
            log.info("xyz");
        }
    }

    assertThat(completionListener.getLastBatchCompletionEvents())
            .hasSize(numLogLines)
            .allSatisfy(evt -> {
                assertThat(evt.source().maxBatchBufferBatches())
                        .isEqualTo(maxBatchBufferBatches);

                assertThat(evt.stats().bufferedBatches())
                        .isLessThanOrEqualTo(maxBatchBufferBatches);
            });
}

@Test
void shouldRespectMaxBatchBytesInSimpleScenario() {
    wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

    final var maxBatchBytes = 300;
    ConfigurationBuilder<BuiltConfiguration> configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
    configBuilder = configBuilder
            .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                    .addAttribute("url", wireMockHttpUrl)
                    .addAttribute("batchPrefix", "(((")
                    .addAttribute("batchSuffix", ")))")
                    .addAttribute("batchSeparator", "#!#")
                    .addAttribute("maxBatchBytes", maxBatchBytes)
                    .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
            .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

    assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
    var config = configBuilder.build(false);

    var longLogLine = "x".repeat(100);
    try (var context = TestHelpers.loggerContextFromConfig(config)) {
        var log = context.getLogger(getClass());
        log.info(longLogLine);
        log.info(longLogLine);
        log.info(longLogLine);
    }

    wireMockExt.verify(2, postRequestedFor(urlEqualTo(wireMockPath)));
}

@ParameterizedTest
@ValueSource(ints = {200, 500, 1000, 2000})
void shouldRespectSmallMaxBatchBytes(int maxBatchBytes) {
    wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

    final var separator = "#!#";
    final var prefix = "begin{";
    final var suffix = "}end";
    var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
    configBuilder = configBuilder
            .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                    .addAttribute("url", wireMockHttpUrl)
                    .addAttribute("batchPrefix", prefix)
                    .addAttribute("batchSuffix", suffix)
                    .addAttribute("batchSeparator", separator)
                    .addAttribute("maxBatchBytes", maxBatchBytes)
                    .addAttribute("maxBatchLogEvents", Integer.MAX_VALUE)
                    .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
            .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

    assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
    var config = configBuilder.build(false);

    var logEvents = 200;
    var logLen = 100;
    var longLogLine = "x".repeat(logLen);
    try (var context = TestHelpers.loggerContextFromConfig(config)) {
        var log = context.getLogger(getClass());

        for (int i = 0; i < logEvents; i++) {
            log.info(longLogLine);
        }
    }

    var requests = wireMockExt.findAll(postRequestedFor(urlEqualTo(wireMockPath)));

    assertThat(requests)
            .isNotEmpty()
            .allSatisfy(r -> assertThat(r.getBody().length).isLessThanOrEqualTo(maxBatchBytes));

    var numSmallerThanNeeded = requests.stream()
            .filter(r -> r.getBody().length + logLen + separator.length() + suffix.length() <= maxBatchBytes)
            .count();

    assertThat(numSmallerThanNeeded).isLessThanOrEqualTo(1);
}

@ParameterizedTest
@ValueSource(ints = {0, 1})
void shouldRespectMaxBatchBytesZeroAndOne(int maxBatchBytes) {
    wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

    var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
    configBuilder = configBuilder
            .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                    .addAttribute("url", wireMockHttpUrl)
                    .addAttribute("maxBatchBytes", maxBatchBytes)
                    .addAttribute("maxBatchLogEvents", Integer.MAX_VALUE)
                    .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
            .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

    assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
    var config = configBuilder.build(false);

    var numLogs = 23;
    try (var context = TestHelpers.loggerContextFromConfig(config)) {
        var log = context.getLogger(getClass());

        for (int i = 0; i < numLogs; i++) {
            log.info("a");
        }
    }

    wireMockExt.verify(numLogs, postRequestedFor(urlEqualTo(wireMockPath)));
}

@Test
void shouldRespectMaxBatchBytesIntMax() {
    wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

    var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
    configBuilder = configBuilder
            .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                    .addAttribute("url", wireMockHttpUrl)
                    .addAttribute("maxBatchBytes", Integer.MAX_VALUE)
                    .addAttribute("maxBatchLogEvents", Integer.MAX_VALUE)
                    .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
            .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

    assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();
    var config = configBuilder.build(false);

    var longLogLine = "a".repeat(1000);
    try (var context = TestHelpers.loggerContextFromConfig(config)) {
        var log = context.getLogger(getClass());

        for (int i = 0; i < 10000; i++) {
            log.info(longLogLine);
        }
    }

    wireMockExt.verify(1, postRequestedFor(urlEqualTo(wireMockPath)));
}

@Test
void shouldRespectShutdownMs() {
    wireMockExt.stubFor(post(wireMockPath).willReturn(ok().withFixedDelay(1)));

    IntToLongFunction logAndClose = shutdownMs -> {
        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("maxBatchLogEvents", 1)
                        .addAttribute("maxConcurrentRequests", 1)
                        .addAttribute("shutdownTimeoutMs", shutdownMs)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

        assertThat(configBuilder.isValid()).isTrue();
        long nanos0;
        try (var context = TestHelpers.loggerContextFromConfig(configBuilder.build())) {
            var log = context.getLogger(getClass());
            nanos0 = System.nanoTime();

            for (int i = 0; i < 25; i++) {
                log.info("test");
            }
        }

        return System.nanoTime() - nanos0;
    };

    var elapsedMillis = IntStream.of(1, 1, 1, 1, 5, 10, 20, -1)
            .mapToLong(logAndClose)
            .skip(3) // <-- the first few iterations are considered "warmup" runs
            .toArray();

    assertThat(elapsedMillis).isSorted();
}

@Test
void shouldDropLogsIfOverloadedAndAggressiveBlockMs() {
    wireMockExt.stubFor(post(wireMockPath).willReturn(ok().withFixedDelay(1)));

    var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
    configBuilder = configBuilder
            .add(configBuilder.newAppender("Count", "CountingAppender"))
            .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                    .addAttribute("url", wireMockHttpUrl)
                    .addAttribute("maxBatchLogEvents", 1)
                    .addAttribute("maxBlockOnOverflowMs", 1)
                    .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg"))
                    .addComponent(configBuilder.newComponent("OverflowAppenderRef").addAttribute("ref", "Count")))
            .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));

    CountingAppender countingAppender;
    var logEvents = new MutableLong();
    try (var context = TestHelpers.loggerContextFromConfig(configBuilder)) {
        countingAppender = TestHelpers.findAppender(context, CountingAppender.class);
        var log = context.getLogger(getClass());

        Runnable logTillDropped = () -> {
            while (countingAppender.currentCount() == 0) {
                log.info("not yet dropped");
                logEvents.increment();
            }
        };

        assertThat(CompletableFuture.runAsync(logTillDropped)).succeedsWithin(5, TimeUnit.SECONDS);
    }

    var expectedRequests = Math.toIntExact(logEvents.longValue() - countingAppender.currentCount());
    wireMockExt.verify(expectedRequests, postRequestedFor(urlEqualTo(wireMockPath)));
}

static AsyncHttpAppender getAsyncHttpAppender(LoggerContext context) {
    return getAsyncHttpAppender(context.getConfiguration());
}

static AsyncHttpAppender getAsyncHttpAppender(Configuration configuration) {
    return configuration.getAppenders().values().stream().flatMap(a -> {
        if (a instanceof AsyncHttpAppender asyncHttpAppender) {
            return Stream.of(asyncHttpAppender);
        } else {
            return Stream.empty();
        }
    }).findFirst().orElseThrow();
}

private void shouldRespectAppenderFilters(LoggerContext context) {
    wireMockExt.stubFor(post(wireMockPath).willReturn(ok()));

    var log = context.getLogger(getClass());
    log.info("x");
    log.info("y");
    log.info("x");
    log.info("y");
    context.close();

    assertThat(collectReceivedLinesPerStatusCode(wireMockPath)).isEqualTo(Map.of(200, Map.of("x", 2)));
}
}
