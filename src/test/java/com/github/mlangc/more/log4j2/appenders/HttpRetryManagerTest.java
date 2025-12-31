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

import com.github.mlangc.more.log4j2.appenders.HttpRetryManager.Config;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatRuntimeException;

class HttpRetryManagerTest {
    static ScheduledThreadPoolExecutor executor;

    @BeforeAll
    static void beforeAll() {
        executor = new ScheduledThreadPoolExecutor(1, new ThreadFactoryBuilder().setDaemon(true).setNameFormat("test-%d").build());
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    record InvalidConfigArgs(int maxRetries, int maxDelayMillis,
                             IntPredicate statusCodeSuccessPredicate,
                             Predicate<Exception> exceptionRetryPredicate,
                             IntPredicate statusCodeRetryPredicate) { }

    static List<InvalidConfigArgs> invalidConfigArgsTestCases() {
        return List.of(
                new InvalidConfigArgs(-1, 1, s -> s == 200, e -> true, s -> s == 500),
                new InvalidConfigArgs(0, 0, s -> s == 200, e -> true, s -> s == 500),
                new InvalidConfigArgs(0, 1, null, e -> true, s -> s == 500),
                new InvalidConfigArgs(0, 1, s -> s == 200, null, s -> s == 500),
                new InvalidConfigArgs(0, 1, s -> s == 200, e -> true, null)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidConfigArgsTestCases")
    void shouldNotAllowInvalidConfigs(InvalidConfigArgs args) {
        assertThatRuntimeException().isThrownBy(
                () -> new HttpRetryManager.Config(
                        args.maxRetries, args.maxDelayMillis, args.statusCodeSuccessPredicate, args.exceptionRetryPredicate, args.statusCodeRetryPredicate));
    }


    @Test
    void shouldBehaveAsExpectedIfRetriesAreDisabled() {
        var retryManager = new HttpRetryManager(new Config(0, 5000, s -> s == 200, e -> false, r -> false), executor);

        var success = new HttpStatus(200, "OK");
        var opsReturningSuccess = List.<Supplier<CompletableFuture<HttpStatus>>>of(
                () -> CompletableFuture.completedFuture(success),
                () -> CompletableFuture.supplyAsync(() -> success),
                () -> CompletableFuture.supplyAsync(() -> success, executor));

        for (var opReturningSuccess : opsReturningSuccess) {
            assertThat(retryManager.run(opReturningSuccess))
                    .succeedsWithin(1, TimeUnit.SECONDS)
                    .satisfies(s -> {
                        assertThat(s.status()).isEqualTo(success);
                        assertThat(s.stats().tries()).isOne();
                    });
        }

        var internalServerError = new HttpStatus(500, "Internal Server Error");
        var opsReturningInternalServerError = List.<Supplier<CompletableFuture<HttpStatus>>>of(
                () -> CompletableFuture.completedFuture(internalServerError),
                () -> CompletableFuture.supplyAsync(() -> internalServerError),
                () -> CompletableFuture.supplyAsync(() -> internalServerError, executor));

        for (var opReturningServerError : opsReturningInternalServerError) {
            assertThat(retryManager.run(opReturningServerError))
                    .completesExceptionallyWithin(1, TimeUnit.SECONDS)
                    .withThrowableThat()
                    .havingCause()
                    .isInstanceOfSatisfying(HttpErrorResponseException.class, e -> {
                        assertThat(e.httpStatus().code()).isEqualTo(500);
                        assertThat(e.httpStatus().message()).isEqualTo("Internal Server Error");
                        assertThat(e.stats().tries()).isOne();
                    });
        }

        var ioException = new IOException("Random IO error");
        var opsReturningIoError = List.<Supplier<CompletableFuture<HttpStatus>>>of(
                () -> CompletableFuture.failedFuture(ioException),
                () -> CompletableFuture.supplyAsync(() -> {
                    throw new UncheckedIOException(ioException);
                }),
                () -> CompletableFuture.supplyAsync(() -> {
                    throw new UncheckedIOException(ioException);
                }, executor));
        for (var opReturningIoError : opsReturningIoError) {
            assertThat(retryManager.run(opReturningIoError))
                    .completesExceptionallyWithin(1, TimeUnit.SECONDS)
                    .withThrowableThat()
                    .havingCause()
                    .isInstanceOf(HttpRequestFailedException.class)
                    .havingRootCause()
                    .isSameAs(ioException);
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.1, 0.05, 0.01, 0.005, 0.004})
    void shouldEventuallyReturnResultWithNonZeroChangeForSuccessAndInfiniteRetires(double chanceOfSuccess) {
        var success = new HttpStatus(200, "OK");
        var serviceUnavailable = new HttpStatus(503, "Service Unavailable");
        var exception = new RuntimeException("Upsala");

        Random random = new Random(42);
        Supplier<CompletableFuture<HttpStatus>> operation = () -> {
            if (random.nextDouble() < chanceOfSuccess) {
                return CompletableFuture.supplyAsync(() -> success, executor);
            } else if (random.nextBoolean()) {
                return CompletableFuture.supplyAsync(() -> { throw exception; }, executor);
            } else {
                return CompletableFuture.supplyAsync(() -> serviceUnavailable, executor);
            }
        };

        var retryManager = new HttpRetryManager(new Config(Integer.MAX_VALUE, 10, s -> s == 200, e -> true, r -> true), executor);

        assertThat(retryManager.run(operation)).succeedsWithin(5, TimeUnit.SECONDS).satisfies(s -> {
            assertThat(s.status()).isEqualTo(success);
            assertThat(s.stats().tries()).isPositive();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void shouldRespectMaxRetries(int maxRetries) {
        var serviceUnavailable = CompletableFuture.completedFuture(new HttpStatus(503, "Service Unavailable"));
        var exception = CompletableFuture.<HttpStatus>failedFuture(new RuntimeException("Upsala"));
        var success = CompletableFuture.completedFuture(new HttpStatus(200, "Juhuu"));

        var operation = new Supplier<CompletableFuture<HttpStatus>>() {
            final AtomicInteger invocations = new AtomicInteger();
            final Random random = new Random(999);

            @Override
            public CompletableFuture<HttpStatus> get() {
                if (invocations.getAndIncrement() <= maxRetries) {
                    return random.nextBoolean() ? serviceUnavailable : exception;
                } else {
                    return success;
                }
            }
        };

        var retryManager1 = new HttpRetryManager(new Config(maxRetries, 1, s -> s == 200, e -> true, r -> true), executor);
        assertThat(retryManager1.run(operation)).completesExceptionallyWithin(1, TimeUnit.SECONDS);
        assertThat(operation.invocations.get()).isEqualTo(maxRetries + 1);

        operation.invocations.set(0);
        var retryManager2 = new HttpRetryManager(new Config(maxRetries + 1, 1, s -> s == 200, e -> true, r -> true), executor);
        assertThat(retryManager2.run(operation)).succeedsWithin(1, TimeUnit.SECONDS);
        assertThat(operation.invocations.get()).isEqualTo(maxRetries + 2);
    }

    @Test
    void shouldRespectRetryExceptionPredicate() {
        var retryableException = new RuntimeException("Upsala");
        var retryManager = new HttpRetryManager(new Config(3, 1, s -> s == 200, e -> e == retryableException, r -> false), executor);

        var operation = new Supplier<CompletableFuture<HttpStatus>>() {
            final AtomicBoolean invoked = new AtomicBoolean();
            RuntimeException exception = retryableException;

            @Override
            public CompletableFuture<HttpStatus> get() {
                if (invoked.compareAndSet(false, true)) {
                    return CompletableFuture.supplyAsync(() -> { throw exception; });
                } else {
                    return CompletableFuture.completedFuture(new HttpStatus(200, "OK"));
                }
            }
        };

        assertThat(retryManager.run(operation)).succeedsWithin(1, TimeUnit.SECONDS);

        operation.exception = new RuntimeException("Not retryable");
        operation.invoked.set(false);
        assertThat(retryManager.run(operation)).completesExceptionallyWithin(1, TimeUnit.SECONDS)
                .withThrowableThat().havingRootCause().withMessageContaining("retryable");
    }

    @Test
    void shouldRespectRetryResponsePredicate() {
        var retryableResponse = new HttpStatus(503, "Service Unavailable");
        var retryManager = new HttpRetryManager(new Config(3, 1, s -> s == 200, e -> false, s -> s == retryableResponse.code()), executor);

        var operation = new Supplier<CompletableFuture<HttpStatus>>() {
            final AtomicBoolean invoked = new AtomicBoolean();
            HttpStatus errorResponse = retryableResponse;

            @Override
            public CompletableFuture<HttpStatus> get() {
                if (invoked.compareAndSet(false, true)) {
                    return CompletableFuture.supplyAsync(() -> errorResponse);
                } else {
                    return CompletableFuture.completedFuture(new HttpStatus(200, "OK"));
                }
            }
        };

        assertThat(retryManager.run(operation)).succeedsWithin(1, TimeUnit.SECONDS);

        operation.errorResponse = new HttpStatus(400, "Bad Request");
        operation.invoked.set(false);
        assertThat(retryManager.run(operation)).completesExceptionallyWithin(1, TimeUnit.SECONDS).withThrowableThat()
                .withMessageContaining("Bad Request")
                .withMessageContaining("400");
    }

    @Test
    void shouldRespectStatusCodeSuccessPredicate() {
        var responseOk = CompletableFuture.completedFuture(new HttpStatus(200, "Ok"));
        var responseNok = CompletableFuture.completedFuture(new HttpStatus(500, "Internal Server Error"));
        var retryManagerOk = new HttpRetryManager(new Config(1, 1, s -> s == 200, e -> true, r -> true), executor);
        var retryManagerNok = new HttpRetryManager(new Config(1, 1, s -> s == 202, e -> true, r -> true), executor);

        var operation = new Supplier<CompletableFuture<HttpStatus>>() {
            final AtomicBoolean invoked = new AtomicBoolean();

            @Override
            public CompletableFuture<HttpStatus> get() {
                if (invoked.compareAndSet(false, true)) {
                    return responseNok;
                } else {
                    return responseOk;
                }
            }
        };

        assertThat(retryManagerOk.run(operation)).succeedsWithin(1, TimeUnit.SECONDS);

        operation.invoked.set(false);
        assertThat(retryManagerNok.run(operation)).completesExceptionallyWithin(1, TimeUnit.SECONDS)
                .withThrowableThat().havingRootCause().withMessageContaining("Ok");
    }

    @Test
    void shouldRespectDisableRetries() throws InterruptedException {
        var retryManager = new HttpRetryManager(new Config(Integer.MAX_VALUE, 1, s -> s == 200, e -> true, s -> true), executor);
        var wouldRetryForever = retryManager.run(() -> CompletableFuture.supplyAsync(() -> new HttpStatus(500, "Ouch")));

        Thread.sleep(5); // wait a bit, so that the retry loop can play out
        retryManager.disableRetries();

        assertThat(wouldRetryForever).completesExceptionallyWithin(1, TimeUnit.SECONDS);
    }

    @Test
    void shouldSetResponseDelayAndTotalNanosToPlausibleValuesOnSuccess1() throws InterruptedException {
        var retryManager = new HttpRetryManager(new Config(0, 1, s -> s == 200, e -> false, s -> false), executor);

        var success = new HttpStatus(200, "Ok");
        var simulatedRequest = new CompletableFuture<HttpStatus>();
        var managedRequest = retryManager.run(() -> simulatedRequest);

        int sleepMs = 50;
        Thread.sleep(sleepMs);
        simulatedRequest.complete(success);

        assertThat(managedRequest).succeedsWithin(1, TimeUnit.SECONDS)
                .satisfies(s -> {
                    assertThat(s.status()).isEqualTo(success);
                    assertThat(s.stats().backoffNanos()).isZero();
                    assertThat(s.stats().requestNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(sleepMs));
                    assertThat(s.stats().totalNanos()).isGreaterThanOrEqualTo(s.stats().backoffNanos() + s.stats().requestNanos());
                });
    }

    @Test
    void shouldSetResponseAndDelayNanosToPlausibleValuesOnSuccess2() throws InterruptedException {
        int maxDelayMillis = 313;
        var retryManager = new HttpRetryManager(new Config(Integer.MAX_VALUE, maxDelayMillis, s -> s == 200, e -> false, s -> s == 500), executor);
        var success = new HttpStatus(200, "Ok");
        var retry = new HttpStatus(500, "Internal Server Error");

        var simulatedRequest = new Supplier<CompletableFuture<HttpStatus>>() {
            int requests;

            @Override
            public CompletableFuture<HttpStatus> get() {
                return CompletableFuture.completedFuture(requests++ == 0 ? retry : success);
            }
        };

        var managedRequest = retryManager.run(simulatedRequest);

        assertThat(managedRequest).succeedsWithin(1, TimeUnit.SECONDS)
                .satisfies(s -> {
                    assertThat(s.status()).isEqualTo(success);
                    assertThat(s.stats().requestNanos()).isLessThan(TimeUnit.MILLISECONDS.toNanos(5));
                    assertThat(s.stats().backoffNanos()).isGreaterThan(0).isLessThan(TimeUnit.MILLISECONDS.toNanos(maxDelayMillis));
                    assertThat(s.stats().totalNanos()).isGreaterThanOrEqualTo(s.stats().backoffNanos() + s.stats().requestNanos());
                });
    }

    @Test
    void shouldSetResponseAndDelayNanosToPlausibleValuesOnFailWithHttp500() {
        final var maxDelayMillis = 111;
        var retryManager = new HttpRetryManager(new Config(1, maxDelayMillis, s -> s == 200, e -> false, s -> s == 500), executor);
        var internalServerError = new HttpStatus(500, "Internal Server Error");

        Supplier<CompletableFuture<HttpStatus>> simulatedRequest = () -> CompletableFuture.completedFuture(internalServerError);
        var managedRequest = retryManager.run(simulatedRequest);

        assertThat(managedRequest).completesExceptionallyWithin(1, TimeUnit.SECONDS)
                .withThrowableThat().havingCause().isInstanceOfSatisfying(HttpErrorResponseException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(internalServerError);
                    assertThat(e.stats().tries()).isEqualTo(2);
                    assertThat(e.stats().backoffNanos()).isPositive().isLessThan(TimeUnit.MILLISECONDS.toNanos(maxDelayMillis));
                    assertThat(e.stats().requestNanos()).isNotNegative().isLessThan(e.stats().backoffNanos());
                    assertThat((e.stats().totalNanos())).isGreaterThanOrEqualTo(e.stats().backoffNanos() + e.stats().requestNanos());
                });
    }

    @Test
    void shouldSetResponseAndDelayNanosToPlausibleValuesOnFailWithException() {
        final var maxDelayMillis = 111;
        var exception = new RuntimeException("retry me");
        var retryManager = new HttpRetryManager(new Config(1, maxDelayMillis, s -> s == 200, e -> e == exception, s -> false), executor);

        Supplier<CompletableFuture<HttpStatus>> simulatedRequest = () -> CompletableFuture.failedFuture(exception);
        var managedRequest = retryManager.run(simulatedRequest);

        assertThat(managedRequest).completesExceptionallyWithin(1, TimeUnit.SECONDS)
                .withThrowableThat().havingCause().isInstanceOfSatisfying(HttpRequestFailedException.class, e -> {
                    assertThat(e).hasCause(exception);
                    assertThat(e.stats().tries()).isEqualTo(2);
                    assertThat(e.stats().backoffNanos()).isPositive().isLessThan(TimeUnit.MILLISECONDS.toNanos(maxDelayMillis));
                    assertThat(e.stats().requestNanos()).isNotNegative().isLessThan(e.stats().backoffNanos());
                    assertThat((e.stats().totalNanos())).isGreaterThanOrEqualTo(e.stats().backoffNanos() + e.stats().requestNanos());
                });
    }

    @AfterAll
    static void afterAll() throws InterruptedException {
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}
