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

import com.github.mlangc.more.log4j2.test.helpers.TestHelpers;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncHttpAppenderWithPartiallyUnavailableBackendTest {
    private WireMockServer wireMockServer;

    @AfterEach
    void afterEach() {
        if (wireMockServer != null) {
            wireMockServer.stop();
            wireMockServer = null;
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void shouldEventuallyConnectEvenIfBackendIsUnavailableOnStartup(int retries) throws IOException, InterruptedException {
        var wireMockPort = findFreePort();
        var wireMockPath = "/logs";
        var wireMockHttpUrl = "http://localhost:" + wireMockPort + wireMockPath;

        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .setStatusLevel(Level.WARN)
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("lingerMs", 1)
                        .addAttribute("connectTimeoutMs", 25)
                        .addAttribute("readTimeoutMs", 25)
                        .addAttribute("retries", retries)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));
        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();

        try (var context = TestHelpers.loggerContextFromConfig(configBuilder.build())) {
            var log = context.getLogger(getClass());

            var uniqueStr1 = "-----@@@help@@@-----";
            log.info(uniqueStr1);
            Thread.sleep(100);

            wireMockServer = new WireMockServer(options().port(wireMockPort));
            wireMockServer.stubFor(post(wireMockPath).willReturn(ok()));
            wireMockServer.start();

            var uniqueStr2 = "!!<<-hello->>!!";
            log.info(uniqueStr2);

            Awaitility.await().atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(wireMockServer.findAll(postRequestedFor(urlEqualTo(wireMockPath))))
                            .isNotEmpty()
                            .noneMatch(r -> r.getBodyAsString().contains(uniqueStr1))
                            .anyMatch(r -> r.getBodyAsString().contains(uniqueStr2)));
        }
    }
    
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void shouldSelfHealIfBackendIsTmpUnavailable(int retries) throws IOException, InterruptedException {
        var wireMockPort = findFreePort();
        var wireMockPath = "/logs";
        var wireMockHttpUrl = "http://localhost:" + wireMockPort + wireMockPath;

        wireMockServer = new WireMockServer(options().port(wireMockPort));
        wireMockServer.stubFor(post(wireMockPath).willReturn(ok()));
        wireMockServer.start();

        var configBuilder = ConfigurationBuilderFactory.newConfigurationBuilder();
        configBuilder = configBuilder
                .setStatusLevel(Level.WARN)
                .add(configBuilder.newAppender("AsyncHttp", "AsyncHttp")
                        .addAttribute("url", wireMockHttpUrl)
                        .addAttribute("lingerMs", 1)
                        .addAttribute("connectTimeoutMs", 25)
                        .addAttribute("readTimeoutMs", 25)
                        .addAttribute("retries", retries)
                        .add(configBuilder.newLayout("PatternLayout").addAttribute("pattern", "%msg")))
                .add(configBuilder.newRootLogger(Level.INFO).add(configBuilder.newAppenderRef("AsyncHttp")));
        assertThat(configBuilder.isValid()).as(configBuilder::toXmlConfiguration).isTrue();

        try (var context = TestHelpers.loggerContextFromConfig(configBuilder.build())) {
            var log = context.getLogger(getClass());

            var uniqueStr1 = "-----@@@:-)@@@-----";
            log.info(uniqueStr1);

            Awaitility.await().atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(wireMockServer.findAll(postRequestedFor(urlEqualTo(wireMockPath))))
                            .anySatisfy(r -> assertThat(r.getBodyAsString()).contains(uniqueStr1)));

            wireMockServer.stop();
            wireMockServer.resetRequests();

            var uniqueStr2 = "-----@@@:-(@@@-----";
            log.info(uniqueStr2);
            Thread.sleep(100);

            wireMockServer.start();

            var uniqueStr3 = "-----@@@+1@@@-----";
            log.info(uniqueStr3);

            Awaitility.await().atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(wireMockServer.findAll(postRequestedFor(urlEqualTo(wireMockPath))))
                            .allSatisfy(r -> assertThat(r.getBodyAsString()).doesNotContain(uniqueStr1).doesNotContain(uniqueStr2))
                            .anySatisfy(r -> assertThat(r.getBodyAsString()).contains(uniqueStr3)));
        }
    }

    static int findFreePort() throws IOException {
        try (var socket = new ServerSocket(0)){
            return socket.getLocalPort();
        }
    }
}
