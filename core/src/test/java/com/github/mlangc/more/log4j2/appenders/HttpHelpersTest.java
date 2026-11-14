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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.function.Function;

import static com.github.mlangc.more.log4j2.appenders.HttpHelpers.parseHttpStatusCodes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HttpHelpersTest {
    record ParseHttpStatusCodeTestCase(String input, int[] expected) {
        static ParseHttpStatusCodeTestCase withValidInput(String input, int... expexted) {
            return new ParseHttpStatusCodeTestCase(input, expexted);
        }

        static ParseHttpStatusCodeTestCase withInvalidInput(String input) {
            return new ParseHttpStatusCodeTestCase(input, null);
        }
    }

    static List<ParseHttpStatusCodeTestCase> parseHttpStatusCodeTestCases() {
        return List.of(
                ParseHttpStatusCodeTestCase.withValidInput(""),
                ParseHttpStatusCodeTestCase.withValidInput("200", 200),
                ParseHttpStatusCodeTestCase.withInvalidInput("99"),
                ParseHttpStatusCodeTestCase.withInvalidInput("1000"),
                ParseHttpStatusCodeTestCase.withInvalidInput("-1"),
                ParseHttpStatusCodeTestCase.withValidInput("200, 202", 200, 202),
                ParseHttpStatusCodeTestCase.withValidInput("200, 200, 202, 204", 200, 202, 204),
                ParseHttpStatusCodeTestCase.withInvalidInput("200,,201,")
        );
    }

    @ParameterizedTest
    @MethodSource("parseHttpStatusCodeTestCases")
    void parseHttpStatusCodesShouldWork(ParseHttpStatusCodeTestCase testCase) {
        if (testCase.expected != null) {
            assertThat(parseHttpStatusCodes(testCase.input)).isEqualTo(testCase.expected);
        } else {
            assertThatIllegalArgumentException().isThrownBy(() -> parseHttpStatusCodes(testCase.input));
        }
    }

    @Test
    void lengthLimitedStringBodyHandlerShouldWorkWhenOnNextIsCalledMultipleTimesWithMultipleBuffers()  {
        var maxBytes = 26;
        var bodySubscriber = HttpHelpers.lengthLimitedStringBodyHandler(maxBytes).apply(new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (ignore1, ignore2) -> true);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        });

        var subscription = new Flow.Subscription() {
            boolean cancelled;

            @Override
            public void request(long n) {

            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        };

        bodySubscriber.onSubscribe(subscription);
        assertThat(subscription.cancelled).isFalse();

        Function<String, ByteBuffer> newBuffer = s -> ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));

        bodySubscriber.onNext(List.of(newBuffer.apply("ab"), newBuffer.apply("cdefghijklmno")));

        assertThat(subscription.cancelled).isFalse();
        bodySubscriber.onNext(List.of(newBuffer.apply("pqrstuvw"), newBuffer.apply("xyz####"), newBuffer.apply("#")));
        assertThat(subscription.cancelled).isFalse();

        bodySubscriber.onComplete();

        assertThat(bodySubscriber.getBody()).isCompletedWithValue("abcdefghijklmnopqrstuvwxyz");
    }
}
