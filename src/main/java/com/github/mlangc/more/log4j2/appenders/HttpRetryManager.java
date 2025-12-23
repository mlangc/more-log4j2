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

    CompletableFuture<HttpStatusWithTries> run(Supplier<CompletableFuture<HttpStatus>> op) {
        return run0(op, 1, startDelayNanos);
    }

    void disableRetries() {
        retriesDisabled = true;
    }

    private CompletableFuture<HttpStatusWithTries> run0(Supplier<CompletableFuture<HttpStatus>> op, int tries, long delayNanos) {
        CompletableFuture<HttpStatusWithTries> outerFuture = new CompletableFuture<>();

        op.get().whenComplete((r, e) -> {
            CompletableFuture<HttpStatusWithTries> innerFuture;
            if (e != null) {
                e = unpackCompletionException(e);

                if (tries > config.maxRetries || !(e instanceof Exception) || !config.exceptionRetryPredicate.test((Exception) e) || retriesDisabled) {
                    innerFuture = CompletableFuture.failedFuture(new HttpRequestFailedException(tries, e));
                } else {
                    innerFuture = scheduleRetry(op, tries, delayNanos);
                }
            } else {
                if (config.statusCodeSuccessPredicate.test(r.code())) {
                    innerFuture = CompletableFuture.completedFuture(new HttpStatusWithTries(r, tries));
                } else if (tries > config.maxRetries || !config.statusCodeRetryPredicate.test(r.code()) || retriesDisabled) {
                    innerFuture = CompletableFuture.failedFuture(new HttpErrorResponseException(tries, r));
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

    private CompletableFuture<HttpStatusWithTries> scheduleRetry(Supplier<CompletableFuture<HttpStatus>> op, int tries, long delayNanos) {
        var res = new CompletableFuture<HttpStatusWithTries>();
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

    record HttpStatusWithTries(HttpStatus status, int tries) { }
}
