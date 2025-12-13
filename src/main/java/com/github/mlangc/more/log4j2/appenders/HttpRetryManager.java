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

import java.util.concurrent.*;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

class HttpRetryManager {
    final Config config;

    private final ScheduledExecutorService executor;
    private final long startDelayNanos;
    private final long maxDelayNanos;
    private volatile boolean retriesDisabled;

    HttpRetryManager(Config config, ScheduledExecutorService executor) {
        this.config = config;
        this.executor = executor;

        this.maxDelayNanos = TimeUnit.MILLISECONDS.toNanos(config.maxDelayMillis);
        this.startDelayNanos = Math.max(1, maxDelayNanos / 15);
    }

    record Config(int maxRetries, int maxDelayMillis, IntPredicate statusCodeSuccessPredicate, Predicate<Exception> exceptionRetryPredicate, IntPredicate statusCodeRetryPredicate) {
        Config {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries=" + maxRetries);
            }

            if (maxDelayMillis < 1) {
                throw new IllegalArgumentException("maxDelayMillis=" + maxDelayMillis);
            }

            requireNonNull(statusCodeSuccessPredicate);
            requireNonNull(exceptionRetryPredicate);
            requireNonNull(statusCodeRetryPredicate);
        }
    }

    <T> CompletableFuture<T> run(Supplier<CompletableFuture<HttpResponseWrapper<T>>> op) {
        return run0(op, 1, startDelayNanos).thenApply(HttpResponseWrapper::payload);
    }

    void disableRetries() {
        retriesDisabled = true;
    }

    private <T> CompletableFuture<HttpResponseWrapper<T>> run0(Supplier<CompletableFuture<HttpResponseWrapper<T>>> op, int tries, long delayNanos) {
        CompletableFuture<HttpResponseWrapper<T>> outerFuture = new CompletableFuture<>();

        op.get().whenComplete((r, e) -> {
            CompletableFuture<HttpResponseWrapper<T>> innerFuture;
            if (e != null) {
                e = unpackCompletionException(e);

                if (tries > config.maxRetries || !(e instanceof Exception) || !config.exceptionRetryPredicate.test((Exception) e) || retriesDisabled) {
                    innerFuture = CompletableFuture.failedFuture(new HttpRequestFailedException("HTTP request failed after " + tries + " tries(s) with an exception", e));
                } else {
                    innerFuture = scheduleRetry(op, tries, delayNanos);
                }
            } else {
                if (config.statusCodeSuccessPredicate.test(r.statusCode())) {
                    innerFuture = CompletableFuture.completedFuture(r);
                } else if (tries > config.maxRetries || !config.statusCodeRetryPredicate.test(r.statusCode()) || retriesDisabled) {
                    innerFuture = CompletableFuture.failedFuture(new HttpRequestFailedException(
                            "HTTP request failed after " + tries + " trie(s) with status code " +  r.statusCode() + " and status message '" + r.statusMessage() + "'"));
                } else {
                    innerFuture = scheduleRetry(op, tries, delayNanos);
                }
            }

            innerFuture.whenComplete((innerRes, innerThrowable) -> {
                if (innerThrowable != null) {
                    outerFuture.completeExceptionally(innerThrowable);
                } else {
                    outerFuture.complete(innerRes);
                }
            });
        });

        return outerFuture;
    }

    private <T> CompletableFuture<HttpResponseWrapper<T>> scheduleRetry(Supplier<CompletableFuture<HttpResponseWrapper<T>>> op, int tries, long delayNanos) {
        var res = new CompletableFuture<HttpResponseWrapper<T>>();
        executor.schedule(() -> {
            run0(op, tries + 1, Math.min(maxDelayNanos, delayNanos * 2)).whenComplete((rr, e) -> {
                if (e == null) {
                    res.complete(rr);
                } else {
                    res.completeExceptionally(e);
                }
            });
        }, ThreadLocalRandom.current().nextLong(delayNanos), TimeUnit.NANOSECONDS);
        return res;
    }

    private static Throwable unpackCompletionException(Throwable throwable) {
        if (throwable instanceof CompletionException) {
            if (throwable.getCause() != null) {
                return throwable.getCause();
            }
        }

        return throwable;
    }
}
