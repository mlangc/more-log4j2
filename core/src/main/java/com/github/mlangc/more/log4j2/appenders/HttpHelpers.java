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

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;

class HttpHelpers {
    private static final Pattern CHARSET_PATTERN = Pattern.compile("(?i);\\s*charset=\"?([^;\"\\s]+)");

    private static Charset charsetFrom(HttpHeaders headers) {
        return headers.firstValue("Content-Type")
                .map(contentType -> {
                    var matcher = CHARSET_PATTERN.matcher(contentType);
                    if (!matcher.find()) {
                        return StandardCharsets.UTF_8;
                    } else {
                        try {
                            return Charset.forName(matcher.group(1));
                        } catch (IllegalArgumentException ignore) {
                            return StandardCharsets.UTF_8;
                        }
                    }
                }).orElse(StandardCharsets.UTF_8);
    }

    static int[] parseHttpStatusCodes(String statusCodes) {
        try {
            statusCodes = statusCodes.trim();

            if (statusCodes.isEmpty()) {
                return new int[0];
            }

            return Arrays.stream(statusCodes.split("\\s*,\\s*"))
                    .mapToInt(Integer::parseInt)
                    .sorted()
                    .distinct()
                    .peek(value -> {
                        if (value < 100 || value > 600) {
                            throw new NumberFormatException("Not a HTTP status code: " + value);
                        }
                    })
                    .toArray();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot parse `" + statusCodes + "` into a list of HTTP status codes", e);
        }
    }

    static HttpResponse.BodyHandler<String> lengthLimitedStringBodyHandler(int maxBytes) {
        return responseInfo -> new HttpResponse.BodySubscriber<>() {
            final HttpResponse.BodySubscriber<String> downstream = HttpResponse.BodySubscribers.ofString(charsetFrom(responseInfo.headers()));
            int remaining = maxBytes;

            @Override
            public CompletionStage<String> getBody() {
                return downstream.getBody();
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                downstream.onSubscribe(subscription);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                int takeElems = 0;
                for (var buffer : buffers) {
                    if (remaining == 0) {
                        break;
                    }

                    if (buffer.remaining() > remaining) {
                        buffer.limit(buffer.position() + remaining);
                    }

                    remaining -= buffer.remaining();
                    takeElems++;
                }

                if (takeElems > 0) {
                    downstream.onNext(buffers.subList(0, takeElems));
                }
            }

            @Override
            public void onError(Throwable throwable) {
                downstream.onError(throwable);
            }

            @Override
            public void onComplete() {
                downstream.onComplete();
            }
        };
    }
}
