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

import org.apache.logging.log4j.core.util.NanoClock;

import java.util.concurrent.*;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

class HttpRetryManager {
    final Config config;

    private final NanoClock nanoClock = System::nanoTime;
    private final ScheduledExecutorService executor;
    private final long startMaxBackoffNanos;
    private final long maxDelayNanos;
    private volatile boolean retriesDisabled;

    HttpRetryManager(Config config, ScheduledExecutorService executor) {
        this.config = config;
        this.executor = executor;

        this.maxDelayNanos = TimeUnit.MILLISECONDS.toNanos(config.maxBackoffMillis);
        this.startMaxBackoffNanos = Math.max(1, maxDelayNanos / 15);
    }

    record Config(int maxRetries, int maxBackoffMillis, IntPredicate statusCodeSuccessPredicate, Predicate<Exception> exceptionRetryPredicate,
                  IntPredicate statusCodeRetryPredicate) {
        Config {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries=" + maxRetries);
            }

            if (maxBackoffMillis < 1) {
                throw new IllegalArgumentException("maxBackoffMillis=" + maxBackoffMillis);
            }

            requireNonNull(statusCodeSuccessPredicate);
            requireNonNull(exceptionRetryPredicate);
            requireNonNull(statusCodeRetryPredicate);
        }
    }

    CompletableFuture<HttpStatusAndStats> run(Supplier<CompletableFuture<HttpStatus>> op) {
        return run0(op, 1, startMaxBackoffNanos, nanoClock.nanoTime(), 0, 0);
    }

    void disableRetries() {
        retriesDisabled = true;
    }

    private CompletableFuture<HttpStatusAndStats> run0(
            Supplier<CompletableFuture<HttpStatus>> op, int tries, long currentMaxBackoffNanos,
            long startNanos, long accumulatedRequestNanos, long accumulatedBackoffNanos) {
        CompletableFuture<HttpStatusAndStats> outerFuture = new CompletableFuture<>();


        var nanos0 = tries == 0 ? startNanos : nanoClock.nanoTime();
        op.get().whenComplete((r, e) -> {
            var newAccumulatedRequestNanos = accumulatedRequestNanos + nanoClock.nanoTime() - nanos0;

            CompletableFuture<HttpStatusAndStats> innerFuture;

            Supplier<RetryStats> retryStats = () ->
                    new RetryStats(tries, newAccumulatedRequestNanos, accumulatedBackoffNanos, nanoClock.nanoTime() - startNanos);

            if (e != null) {
                e = unpackCompletionException(e);

                if (tries > config.maxRetries || !(e instanceof Exception) || !config.exceptionRetryPredicate.test((Exception) e) || retriesDisabled) {
                    innerFuture = CompletableFuture.failedFuture(new HttpRequestFailedException(retryStats.get(), e));
                } else {
                    innerFuture = scheduleRetry(op, tries, currentMaxBackoffNanos, startNanos, newAccumulatedRequestNanos, accumulatedBackoffNanos);
                }
            } else {
                if (config.statusCodeSuccessPredicate.test(r.code())) {
                    innerFuture = CompletableFuture.completedFuture(new HttpStatusAndStats(r, retryStats.get()));
                } else if (tries > config.maxRetries || !config.statusCodeRetryPredicate.test(r.code()) || retriesDisabled) {
                    innerFuture = CompletableFuture.failedFuture(new HttpErrorResponseException(r, retryStats.get()));
                } else {
                    innerFuture = scheduleRetry(op, tries, currentMaxBackoffNanos, startNanos, newAccumulatedRequestNanos, accumulatedBackoffNanos);
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

    private CompletableFuture<HttpStatusAndStats> scheduleRetry(Supplier<CompletableFuture<HttpStatus>> op, int tries,
                                                                long currentMaxBackoffNanos, long startNanos, long accumulatedRequestNanos, long accumulatedBackoffNanos) {
        var res = new CompletableFuture<HttpStatusAndStats>();
        long backoffNanos = ThreadLocalRandom.current().nextLong(currentMaxBackoffNanos);
        executor.schedule(() -> {
            run0(op, tries + 1, Math.min(maxDelayNanos, currentMaxBackoffNanos * 2), startNanos, accumulatedRequestNanos, accumulatedBackoffNanos + backoffNanos)
                    .whenComplete((rr, e) -> {
                        if (e == null) {
                            res.complete(rr);
                        } else {
                            res.completeExceptionally(e);
                        }
                    });
        }, backoffNanos, TimeUnit.NANOSECONDS);
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

    record HttpStatusAndStats(HttpStatus status, RetryStats stats) { }
}
