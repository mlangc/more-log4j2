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

import com.github.mlangc.more.log4j2.appenders.HttpRetryManager.HttpStatusAndStats;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AbstractManager;
import org.apache.logging.log4j.core.config.AppenderControl;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.*;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;
import org.apache.logging.log4j.core.util.Log4jThreadFactory;
import org.apache.logging.log4j.core.util.NanoClock;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.status.StatusLogger;

import javax.annotation.concurrent.GuardedBy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import static java.util.Objects.requireNonNull;

@Plugin(name = "AsyncHttp", category = Node.CATEGORY, elementType = Appender.ELEMENT_TYPE, printObject = true)
public class AsyncHttpAppender extends AbstractAppender {
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private final URI url;
    private final int maxBatchBytes;
    private final long lingerNs;
    private final int maxConcurrentRequests;
    private final int maxBatchLogEvents;
    private final int maxBatchBufferBytes;
    private final HttpClientManager httpClientManager;
    private final NanoClock ticker;
    private final Configuration configuration;
    private final HttpMethod method;
    private final byte[] batchPrefix;
    private final byte[] batchSeparator;
    private final byte[] batchSuffix;
    private final ContentEncoding contentEncoding;
    private final BatchSeparatorInsertionStrategy batchSeparatorInsertionStrategy;
    private final HttpRetryManager retryManager;
    private final int[] httpSuccessCodes;
    private final int[] httpRetryCodes;
    private final boolean retryOnIoError;
    private final BatchCompletionListener batchCompletionListener;
    private final int shutdownTimeoutMs;
    private final int maxBlockOnOverflowMs;
    private final int maxBatchBufferBatches;
    private final OverflowAppenderRef overflowAppenderRef;

    private final Set<FutureHolder> trackedFutures = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Lock lock = new ReentrantLock();
    private final Condition lockCondition;

    private final Semaphore allowedInFlight;

    @GuardedBy("lock")
    private final List<byte[]> currentBatch = new ArrayList<>();
    @GuardedBy("lock")
    private long currentBatchBytes;
    @GuardedBy("lock")
    private final Deque<Batch> bufferedBatches;
    @GuardedBy("lock")
    private long currentBufferBytes;
    @GuardedBy("lock")
    private long firstRecordNanos;
    @GuardedBy("lock")
    private ScheduledFuture<?> scheduledFlush;

    private AppenderControl overflowAppenderControl;

    private record Batch(byte[] data, long uncompressedBytes, int logEvents) {
        int effectiveBytes() {
            return data.length;
        }
    }

    private static class FutureHolder {
        final String jobName;
        volatile CompletableFuture<?> future;

        FutureHolder(String jobName) {
            this.jobName = jobName;
        }
    }

    public sealed interface BatchCompletionType { }

    public record BatchDeliveredSuccess(HttpStatus httpStatus) implements BatchCompletionType { }

    public record BatchDeliveredError(HttpStatus httpStatus) implements BatchCompletionType { }

    public record BatchDeliveryFailed(Exception exception) implements BatchCompletionType { }

    public record BatchCompletionStats(long batchBytesUncompressed, int batchBytesEffective, int batchLogEvents, long bufferedBatchBytes,
                                       int bufferedBatches, int tries, long backoffNanos, long requestNanos, long totalNanos) { }

    public record BatchCompletionEvent(AsyncHttpAppender source, BatchCompletionStats stats, BatchCompletionType type) { }

    public interface BatchCompletionListener {
        void onBatchCompletionEvent(BatchCompletionEvent completionEvent);
    }

    public enum HttpMethod {
        POST, PUT
    }

    public enum ContentEncoding {
        IDENTITY, GZIP
    }

    public enum BatchSeparatorInsertionStrategy {
        ALWAYS, IF_MISSING
    }

    AsyncHttpAppender(
            String name, URI url, Filter filter, Layout<? extends Serializable> layout, boolean ignoreExceptions, Property[] properties,
            int maxBatchBytes, int lingerMs, int maxConcurrentRequests,
            int maxBatchLogEvents, HttpClientManager httpClientManager, NanoClock ticker, Configuration configuration, HttpMethod method,
            byte[] batchPrefix, byte[] batchSeparator, byte[] batchSuffix, int retries, ContentEncoding contentEncoding, int maxBatchBufferBytes, int[] httpSuccessCodes, int[] httpRetryCodes,
            boolean retryOnIoError, BatchSeparatorInsertionStrategy batchSeparatorInsertionStrategy, String batchCompletionListener, int shutdownTimeoutMs,
            int maxBlockOnOverflowMs, int maxBatchBufferBatches, OverflowAppenderRef overflowAppenderRef, int maxBackoffMs) {
        super(name, filter, layout, ignoreExceptions, properties);

        if (maxBatchBufferBytes <= 0) {
            throw new IllegalArgumentException("maxBatchBufferBytes must not by <= 0, but got " + maxBatchBufferBytes);
        }

        if (maxBatchLogEvents <= 0) {
            throw new IllegalArgumentException("maxBatchLogEvents must not by < 0, but got " + maxBatchLogEvents);
        }

        if (httpSuccessCodes.length == 0) {
            throw new IllegalArgumentException("httpSuccessCodes most not be empty; please specify at least one code, like 200");
        }

        if (lingerMs < 1) {
            throw new IllegalArgumentException("lingerMs must not be smaller than 1, but got " + lingerMs);
        }

        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("maxConcurrentRequests must not be smaller than 1, but got " + maxConcurrentRequests);
        }

        if (intersects(httpSuccessCodes, httpRetryCodes)) {
            throw new IllegalArgumentException(
                    "httpSuccessCodes=" + Arrays.toString(httpSuccessCodes) + " and httpRetryCodes=" + Arrays.toString(httpRetryCodes) + " must not intersect");
        }

        if (shutdownTimeoutMs < 0) {
            throw new IllegalArgumentException("shutdownTimeoutMs most not be negative, but got " + shutdownTimeoutMs);
        }

        if (maxBackoffMs < 1) {
            throw new IllegalArgumentException("maxBackoffMs must not be smaller than 1, but got " + maxBackoffMs);
        }

        this.shutdownTimeoutMs = shutdownTimeoutMs;
        this.maxBlockOnOverflowMs = maxBlockOnOverflowMs;
        this.maxBatchBufferBatches = maxBatchBufferBatches;
        this.overflowAppenderRef = overflowAppenderRef;

        this.url = requireNonNull(url);
        this.maxBatchBytes = maxBatchBytes;
        this.maxBatchBufferBytes = maxBatchBufferBytes;
        this.lingerNs = TimeUnit.MILLISECONDS.toNanos(lingerMs);
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.allowedInFlight = new Semaphore(maxConcurrentRequests);
        this.maxBatchLogEvents = maxBatchLogEvents;
        this.httpClientManager = requireNonNull(httpClientManager);
        this.ticker = requireNonNull(ticker);
        this.configuration = requireNonNull(configuration);
        this.method = requireNonNull(method);
        this.batchPrefix = requireNonNull(batchPrefix);
        this.batchSeparator = requireNonNull(batchSeparator);
        this.batchSuffix = requireNonNull(batchSuffix);
        this.contentEncoding = requireNonNull(contentEncoding);
        this.batchSeparatorInsertionStrategy = requireNonNull(batchSeparatorInsertionStrategy);
        this.bufferedBatches = new ArrayDeque<>();
        this.httpSuccessCodes = httpSuccessCodes;
        this.httpRetryCodes = httpRetryCodes;
        this.retryOnIoError = retryOnIoError;

        var retryConfig = new HttpRetryManager.Config(
                retries,
                maxBackoffMs,
                s -> contains(s, httpSuccessCodes),
                retryOnIoError ? e -> e instanceof IOException : e -> false,
                s -> contains(s, httpRetryCodes));

        this.retryManager = new HttpRetryManager(retryConfig, httpClientManager.executor);

        if (batchCompletionListener != null && !batchCompletionListener.isEmpty()) {
            this.batchCompletionListener = newInstanceFromConfiguredClassName(BatchCompletionListener.class, batchCompletionListener);
        } else {
            this.batchCompletionListener = AsyncHttpAppender::defaultOnBatchCompletionEventListener;
        }

        if (maxBlockOnOverflowMs > 0) {
            lockCondition = lock.newCondition();
        } else {
            lockCondition = null;
        }
    }

    @Override
    public void start() {
        if (overflowAppenderRef != null) {
            doWithLock(() -> {
                var overflowAppender = requireNonNull(configuration.<Appender>getAppender(overflowAppenderRef.ref()));
                overflowAppenderControl = new AppenderControl(overflowAppender, overflowAppenderRef.level(), overflowAppenderRef.filter());
            });
        }

        super.start();
    }

    private static boolean intersects(int[] left, int[] right) {
        for (var l : left) {
            if (contains(l, right)) {
                return true;
            }
        }

        return false;
    }

    private static boolean contains(int search, int[] elems) {
        for (var code : elems) {
            if (code == search) {
                return true;
            }
        }

        return false;
    }

    @PluginFactory
    public static AsyncHttpAppender create(
            @Required @PluginAttribute("name") String name,
            @Required @PluginAttribute("url") URI url,
            @PluginAttribute(value = "lingerMs", defaultInt = 5000) int lingerMs,
            @PluginAttribute(value = "maxBatchBytes", defaultInt = 250_000) int maxBatchBytes,
            @PluginAttribute(value = "maxBatchBufferBytes", defaultInt = Integer.MIN_VALUE) int maxBatchBufferBytes,
            @PluginAttribute(value = "maxBatchBufferBatches", defaultInt = 50) int maxBatchBufferBatches,
            @PluginAttribute(value = "maxBatchLogEvents", defaultInt = 1000) int maxBatchLogEvents,
            @PluginAttribute(value = "ignoreExceptions", defaultBoolean = true) boolean ignoreExceptions,
            @PluginAttribute(value = "connectTimeoutMs", defaultInt = 10_000) int connectTimeoutMs,
            @PluginAttribute(value = "readTimeoutMs", defaultInt = 10_000) int readTimeoutMs,
            @PluginAttribute(value = "maxConcurrentRequests", defaultInt = 5) int maxConcurrentRequests,
            @PluginAttribute(value = "method", defaultString = "POST") HttpMethod method,
            @PluginAttribute(value = "batchPrefix") String batchPrefix,
            @PluginAttribute(value = "batchSeparator") String batchSeparator,
            @PluginAttribute(value = "batchSuffix") String batchSuffix,
            @PluginAttribute(value = "batchSeparatorInsertionStrategy", defaultString = "if_missing") BatchSeparatorInsertionStrategy batchSeparatorInsertionStrategy,
            @PluginAttribute(value = "retries", defaultInt = 5) int retries,
            @PluginAttribute(value = "maxBackoffMs", defaultInt = 10_000) int maxBackoffMs,
            @PluginAttribute(value = "httpSuccessCodes", defaultString = "200,202,204") String httpSuccessCodes,
            @PluginAttribute(value = "httpRetryCodes", defaultString = "429,500,502,503,504") String httpRetryCodes,
            @PluginAttribute(value = "retryOnIoError", defaultBoolean = true) boolean retryOnIoError,
            @PluginAttribute(value = "contentEncoding", defaultString = "identity") ContentEncoding contentEncoding,
            @PluginAttribute(value = "httpClientSslConfigSupplier") String httpClientSslConfigSupplier,
            @PluginAttribute(value = "batchCompletionListener") String batchCompletionListener,
            @PluginAttribute(value = "shutdownTimeoutMs", defaultInt = 15_000) int shutdownTimeoutMs,
            @PluginAttribute(value = "maxBlockOnOverflowMs") int maxBlockOnOverflowMs,
            @PluginElement("Filter") Filter filter,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginElement("Properties") Property[] properties,
            @PluginElement("OverflowAppenderRef") OverflowAppenderRef overflowAppenderRef,
            @PluginConfiguration Configuration configuration) {
        var managerData = new HttpClientManagerData(Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(readTimeoutMs), httpClientSslConfigSupplier);

        if (batchSeparator == null) {
            batchSeparator = "\n";
        }

        return new AsyncHttpAppender(name, url, filter, layout, ignoreExceptions, properties,
                maxBatchBytes, lingerMs, maxConcurrentRequests, maxBatchLogEvents,
                HttpClientManager.get(managerData), System::nanoTime, configuration, method,
                batchPrefix == null ? EMPTY_BYTE_ARRAY : batchPrefix.getBytes(StandardCharsets.UTF_8),
                batchSeparator.getBytes(StandardCharsets.UTF_8),
                batchSuffix == null ? EMPTY_BYTE_ARRAY : batchSuffix.getBytes(StandardCharsets.UTF_8),
                retries, contentEncoding,
                maxBatchBufferBytes == Integer.MIN_VALUE
                        ? maxBatchBufferBytesFromMaxBatchBytes(maxBatchBytes, maxBatchBufferBatches)
                        : maxBatchBufferBytes,
                HttpHelpers.parseHttpStatusCodes(httpSuccessCodes),
                HttpHelpers.parseHttpStatusCodes(httpRetryCodes),
                retryOnIoError, batchSeparatorInsertionStrategy, batchCompletionListener,
                shutdownTimeoutMs < 0 ? Integer.MAX_VALUE : shutdownTimeoutMs,
                maxBlockOnOverflowMs < 0 ? Integer.MAX_VALUE : maxBlockOnOverflowMs,
                maxBatchBufferBatches, overflowAppenderRef, maxBackoffMs);
    }

    private static int maxBatchBufferBytesFromMaxBatchBytes(long maxBatchBytes, int maxBatchBufferBatches) {
        var maxBatchBufferBytes0 = 250_000;
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, maxBatchBytes * maxBatchBufferBatches + maxBatchBufferBytes0));
    }

    @Override
    public void append(LogEvent event) {
        AppenderControl useOverflowAppenderControl = null;

        // This might eventually be migrated to use `Encoder.encode`, but for the
        // time being using byte arrays seems good enough.
        byte[] eventBytes = getLayout().toByteArray(event);

        lock.lock();
        try {
            var overflow = false;
            if (currentBatch.isEmpty()) {
                firstRecordNanos = ticker.nanoTime();

                if (scheduledFlush != null) {
                    scheduledFlush.cancel(false);
                }

                scheduledFlush = executor().schedule(this::flushIfLingerElapsed, lingerNs, TimeUnit.NANOSECONDS);
            }

            if (needsFlushAssumeLocked(eventBytes)) {
                overflow = !tryFlushAssumingLocked();
            }

            if (!overflow) {
                if (currentBatchBytes == 0) {
                    currentBatchBytes += batchPrefix.length;
                }

                currentBatch.add(eventBytes);
                currentBatchBytes += eventBytes.length;
            } else {
                if (lockCondition != null) {
                    var remainingNanos = TimeUnit.MILLISECONDS.toNanos(maxBlockOnOverflowMs);
                    do {
                        remainingNanos = lockCondition.awaitNanos(remainingNanos);

                        if (!needsFlushAssumeLocked(eventBytes) || tryFlushAssumingLocked()) {
                            currentBatch.add(eventBytes);
                            currentBatchBytes += batchPrefix.length;
                            currentBatchBytes += eventBytes.length;
                            overflow = false;
                            break;
                        }
                    } while (remainingNanos > 0);
                }

                if (overflow) {
                    // We could forward the event here directly, but it seems slightly preferable to do this after releasing the lock.
                    useOverflowAppenderControl = overflowAppenderControl;
                }
            }
        } catch (InterruptedException e) {
            getStatusLogger().warn("Interrupted while waiting for free slot in batch buffer", e);
            Thread.currentThread().interrupt();
            return;
        } finally {
            lock.unlock();
        }

        if (useOverflowAppenderControl != null) {
            useOverflowAppenderControl.callAppender(event);
        }
    }

    private boolean needsFlushAssumeLocked(byte[] eventBytes) {
        if (currentBatch.isEmpty()) {
            return false;
        } else if (currentBatch.size() >= maxBatchLogEvents) {
            return true;
        } else {
            return currentBatchBytes + batchSeparator.length + batchSuffix.length + eventBytes.length > maxBatchBytes;
        }
    }

    private void doWithLock(Runnable op) {
        lock.lock();
        try {
            op.run();
        } finally {
            lock.unlock();
        }
    }

    private void flushIfLingerElapsed() {
        doWithLock(() -> {
            if (currentBatch.isEmpty()) {
                return;
            }

            long elapsed = ticker.nanoTime() - firstRecordNanos;
            if (elapsed > Math.round(lingerNs * 0.95)) {
                tryFlushAssumingLocked();
            }
        });
    }

    private boolean tryFlushAssumingLocked() {
        if (currentBatch.isEmpty()) {
            return true;
        }

        var batchSize = calculateBatchSize(currentBatch);

        // Implementation note: The effective batch size might be smaller, or even bigger in exceptional cases if compression is enabled.
        // However, since creating batches just to throw them away seems wasteful, especially in an overload situation, this inaccuracy seems the lesser evil.
        if ((batchSize + currentBufferBytes <= maxBatchBufferBytes || currentBufferBytes == 0) && bufferedBatches.size() < maxBatchBufferBatches) {
            // Implementation note: Creating a batch can be a relatively expensive operation, especially if compression is enabled.
            // It might therefore be preferable to release the lock before doing so, at least under specific conditions.
            Batch batch = createBatch(currentBatch, batchSize);
            currentBufferBytes += batch.effectiveBytes();
            bufferedBatches.addLast(batch);
            runAsyncTracked("drainBufferedBatches", () -> drainBufferedBatches(lingerNs / 20), () -> { });
            currentBatch.clear();
            currentBatchBytes = 0;
            return true;
        }

        return false;
    }

    private void drainBufferedBatches(long maxBackoffNs) {
        doWithLock(() -> {
            while (true) {
                var oldestBatch = bufferedBatches.peekFirst();
                if (oldestBatch == null) {
                    break;
                }

                if (!allowedInFlight.tryAcquire()) {
                    var backoffNs = ThreadLocalRandom.current().nextLong(maxBackoffNs + 1);
                    var newMaxBackoffNs = Math.min(lingerNs, maxBackoffNs * 2);
                    executor().schedule(() -> drainBufferedBatches(newMaxBackoffNs), backoffNs, TimeUnit.NANOSECONDS);
                    getStatusLogger().debug("Too many request in flight; trying later");
                    break;
                }

                bufferedBatches.removeFirst();
                int releaseBytes = oldestBatch.data.length;
                long uncompressedBytes = oldestBatch.uncompressedBytes();
                int logEvents = oldestBatch.logEvents();

                runAsyncTracked(
                        "sendBatchBytes",
                        (Supplier<CompletableFuture<HttpStatusAndStats>>) () -> retryManager.run(() -> sendBatch(oldestBatch)),
                        (response, throwable) -> {
                            allowedInFlight.release();

                            long bufferBytesSnapshot;
                            int bufferedBatchesSnapshot;
                            lock.lock();
                            try {
                                currentBufferBytes -= releaseBytes;
                                bufferBytesSnapshot = currentBufferBytes;
                                bufferedBatchesSnapshot = bufferedBatches.size();

                                if (lockCondition != null) {
                                    lockCondition.signalAll();
                                }
                            } finally {
                                lock.unlock();
                            }

                            BatchCompletionType completionType = null;
                            RetryStats retryStats = null;
                            if (response != null) {
                                completionType = new BatchDeliveredSuccess(response.status());
                                retryStats = response.stats();
                            } else if (throwable instanceof HttpErrorResponseException errorResponseException) {
                                completionType = new BatchDeliveredError(errorResponseException.httpStatus());
                                retryStats = errorResponseException.stats();
                            } else if (throwable instanceof HttpRetryManagerException retryManagerException) {
                                completionType = new BatchDeliveryFailed(retryManagerException);
                                retryStats = retryManagerException.stats();
                            } else if (throwable instanceof Exception exception) {
                                completionType = new BatchDeliveryFailed(exception);
                                retryStats = new RetryStats(-1, -1, -1, -1);
                            }

                            if (completionType != null) {
                                batchCompletionListener.onBatchCompletionEvent(new BatchCompletionEvent(
                                        this,
                                        new BatchCompletionStats(uncompressedBytes, releaseBytes, logEvents, bufferBytesSnapshot, bufferedBatchesSnapshot,
                                                retryStats.tries(), retryStats.backoffNanos(), retryStats.requestNanos(), retryStats.totalNanos()),
                                        completionType));
                            }
                        });
            }
        });
    }

    private CompletableFuture<HttpStatus> sendBatch(Batch batch) {
        var requestBuilder = HttpRequest.newBuilder()
                .uri(url)
                .timeout(httpClientManager.readTimeout)
                .method(method.name(), BodyPublishers.ofByteArray(batch.data));

        for (Property property : getPropertyArray()) {
            requestBuilder.header(property.getName(), property.evaluate(configuration.getStrSubstitutor()));
        }

        if (contentEncoding != ContentEncoding.IDENTITY) {
            requestBuilder.header("Content-Encoding", contentEncoding.name().toLowerCase(Locale.ROOT));
        }

        return httpClientManager.httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply((response -> new HttpStatus(response.statusCode(), response.body())));
    }

    private record HttpClientManagerData(Duration connectTimeout, Duration readTimeout, String httpClientSslConfigSupplier) {
        String managerName() {
            return HttpClientManager.class.getSimpleName() + "@" + this;
        }
    }

    private static <T> T newInstanceFromConfiguredClassName(Class<T> baseClass, String className) {
        try {
            var clazz = Class.forName(className);
            return baseClass.cast(clazz.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot load" + baseClass.getSimpleName() + " implementation from '" + className + "'", e);
        }
    }

    private static class HttpClientManager extends AbstractManager {
        final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1, Log4jThreadFactory.createDaemonThreadFactory(AsyncHttpAppender.class.getSimpleName()));

        final HttpClient httpClient;
        final Duration readTimeout;
        final String httpClientSslConfigSupplier;

        HttpClientManager(HttpClientManagerData managerData) {
            super(null, managerData.managerName());
            this.readTimeout = managerData.readTimeout;
            this.httpClientSslConfigSupplier = managerData.httpClientSslConfigSupplier;
            this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

            var httpClientBuilder = HttpClient.newBuilder()
                    .executor(executor)
                    .connectTimeout(managerData.connectTimeout);

            HttpClientSslConfig httpClientSslConfig = null;
            if (managerData.httpClientSslConfigSupplier != null && !managerData.httpClientSslConfigSupplier.isEmpty()) {
                try {
                    var supplier = newInstanceFromConfiguredClassName(HttpClientSslConfigSupplier.class, managerData.httpClientSslConfigSupplier);
                    httpClientSslConfig = supplier.get();
                } catch (Exception e) {
                    throw new IllegalArgumentException("Cannot load SSL configuration from '" + managerData.httpClientSslConfigSupplier + "'", e);
                }
            }

            if (httpClientSslConfig != null) {
                if (httpClientSslConfig.sslContext() != null) {
                    httpClientBuilder.sslContext(httpClientSslConfig.sslContext());
                }

                if (httpClientSslConfig.sslParameters() != null) {
                    httpClientBuilder.sslParameters(httpClientSslConfig.sslParameters());
                }
            }

            this.httpClient = httpClientBuilder.build();
        }

        static HttpClientManager get(HttpClientManagerData managerData) {
            return AbstractManager.getManager(managerData.managerName(),
                    (ignore1, data) -> new HttpClientManager(data), managerData);
        }

        @Override
        protected boolean releaseSub(long timeout, TimeUnit timeUnit) {
            executor.shutdown();

            try {
                var stoppedCleanly = executor.awaitTermination(timeout, timeUnit);

                if (!stoppedCleanly) {
                    getStatusLogger().warn("Executor owned by {} was not stopped cleanly after {} {}", getClass(), timeout, timeUnit);
                }

                return stoppedCleanly;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                getStatusLogger().info("Terminated");
            }
        }
    }

    private static boolean endsWith(byte[] data, byte[] pattern) {
        if (pattern.length > data.length) {
            return false;
        }

        return Arrays.equals(data, data.length - pattern.length, data.length, pattern, 0, pattern.length);
    }

    private static boolean startsWith(byte[] data, byte[] pattern) {
        if (pattern.length > data.length) {
            return false;
        }

        return Arrays.equals(data, 0, pattern.length, pattern, 0, pattern.length);
    }

    private boolean hasUnpublishedLogData() {
        lock.lock();
        try {
            return currentBufferBytes > 0 || currentBatchBytes > 0;
        } finally {
            lock.unlock();
        }
    }

    URI url() {
        return url;
    }

    int lingerMs() {
        return Math.toIntExact(TimeUnit.NANOSECONDS.toMillis(lingerNs));
    }

    int maxBackoffMs() {
        return retryManager.config.maxBackoffMs();
    }

    public int maxBatchBytes() {
        return maxBatchBytes;
    }

    public int maxBatchBufferBytes() {
        return maxBatchBufferBytes;
    }

    int connectTimeoutMs() {
        return Math.toIntExact(httpClientManager.httpClient.connectTimeout().orElseThrow().toMillis());
    }

    int readTimeoutMs() {
        return Math.toIntExact(httpClientManager.readTimeout.toMillis());
    }

    int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public int maxBatchLogEvents() {
        return maxBatchLogEvents;
    }

    String batchSeparator() {
        return new String(batchSeparator, StandardCharsets.UTF_8);
    }

    String batchPrefix() {
        return new String(batchPrefix, StandardCharsets.UTF_8);
    }

    String batchSuffix() {
        return new String(batchSuffix, StandardCharsets.UTF_8);
    }

    HttpMethod method() {
        return method;
    }

    BatchSeparatorInsertionStrategy batchSeparatorInsertionStrategy() {
        return batchSeparatorInsertionStrategy;
    }

    String httpClientSslConfigSupplier() {
        return httpClientManager.httpClientSslConfigSupplier;
    }

    int maxBlockOnOverflowMs() {
        return maxBlockOnOverflowMs;
    }

    int shutdownTimeoutMs() {
        return shutdownTimeoutMs;
    }

    OverflowAppenderRef overflowAppenderRef() {
        return overflowAppenderRef;
    }

    public int maxBatchBufferBatches() {
        return maxBatchBufferBatches;
    }

    public BatchCompletionListener batchCompletionListener() {
        return batchCompletionListener;
    }

    private int calculateBatchSize(List<byte[]> events) {
        assert !currentBatch.isEmpty();

        int batchSize = batchPrefix.length + batchSuffix.length;
        batchSize += events.get(0).length;

        for (int i = 1; i < events.size(); i++) {
            if (isSeparatorNeeded(events.get(i - 1), events.get(i))) {
                batchSize += batchSeparator.length;
            }

            batchSize += events.get(i).length;
        }

        return batchSize;
    }

    private Batch createBatch(List<byte[]> events, int batchSize) {
        byte[] res = new byte[batchSize];
        System.arraycopy(batchPrefix, 0, res, 0, batchPrefix.length);

        int i0 = batchPrefix.length;
        System.arraycopy(events.get(0), 0, res, i0, events.get(0).length);
        i0 += events.get(0).length;

        for (int i = 1; i < events.size(); i++) {
            if (isSeparatorNeeded(events.get(i - 1), events.get(i))) {
                System.arraycopy(batchSeparator, 0, res, i0, batchSeparator.length);
                i0 += batchSeparator.length;
            }

            byte[] event = events.get(i);
            System.arraycopy(event, 0, res, i0, event.length);
            i0 += event.length;
        }

        System.arraycopy(batchSuffix, 0, res, i0, batchSuffix.length);

        var uncompressedBytes = res.length;
        if (contentEncoding == ContentEncoding.GZIP) {
            try {
                var bout = new ByteArrayOutputStream(256);
                try (var gzipOut = new GZIPOutputStream(bout)) {
                    gzipOut.write(res);
                }
                res = bout.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        return new Batch(res, uncompressedBytes, events.size());
    }

    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        if (timeout <= 0) {
            timeout = shutdownTimeoutMs;
            timeUnit = TimeUnit.MILLISECONDS;
        }

        setStopping();
        doWithLock(() -> {
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }

            tryFlushAssumingLocked();
        });

        var stoppedCleanly = true;
        try {
            var waitBeforeDisablingRetriesMillis = timeUnit.toMillis(timeout) - Math.round(retryManager.config.maxBackoffMs() * 1.1);

            if (waitBeforeDisablingRetriesMillis <= 0) {
                retryManager.disableRetries();
            } else {
                executor().schedule(retryManager::disableRetries, waitBeforeDisablingRetriesMillis, TimeUnit.MILLISECONDS);
            }

            var remainingNanos = timeUnit.toNanos(timeout);
            while (remainingNanos > 0 && hasUnpublishedLogData()) {
                var t0 = ticker.nanoTime();

                doWithLock(this::tryFlushAssumingLocked);
                CompletableFuture.allOf(currentlyTrackedFutures())
                        .exceptionally(ignore -> null) // <-- we only care about the future terminating at all.
                        .get(remainingNanos, TimeUnit.NANOSECONDS);

                var elapsed = ticker.nanoTime() - t0;
                remainingNanos -= elapsed;
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            stoppedCleanly = false;
        } finally {
            // Without this, retries might be kept enabled in the case where we scheduled disableRetries above, but shutdown the executor before the schedule runs below.
            retryManager.disableRetries();
        }

        super.stop(timeout, timeUnit, false);
        stoppedCleanly &= httpClientManager.stop(timeout, timeUnit);
        stoppedCleanly &= !hasUnpublishedLogData();

        if (hasUnpublishedLogData()) {
            getStatusLogger().warn("Not all logs could be published to HTTP backend while shutting down. Up to {} bytes of log data might be dropped.",
                    currentBufferBytes + currentBatchBytes /* racy access without lock just for logging an estimate is ok */);
        }

        setStopped();
        return stoppedCleanly;
    }

    private CompletableFuture<?>[] currentlyTrackedFutures() {
        var currentlyTracked = new ArrayList<CompletableFuture<?>>();
        for (var trackedFuture : trackedFutures) {
            var future = trackedFuture.future;
            if (future != null) {
                currentlyTracked.add(future);
            }
        }

        return currentlyTracked.toArray(CompletableFuture<?>[]::new);
    }

    private ScheduledThreadPoolExecutor executor() {
        return httpClientManager.executor;
    }

    int retries() {
        return retryManager.config.maxRetries();
    }

    ContentEncoding contentEncoding() {
        return contentEncoding;
    }

    int[] httpSuccessCodes() {
        return httpSuccessCodes.clone();
    }

    int[] httpRetryCodes() {
        return httpRetryCodes.clone();
    }

    boolean retryOnIoError() {
        return retryOnIoError;
    }

    private <T> void runAsyncTracked(String jobName, Supplier<CompletableFuture<T>> lazyAsyncOp, BiConsumer<T, Throwable> afterOp) {
        FutureHolder holder = new FutureHolder(jobName);
        trackedFutures.add(holder);

        try {
            holder.future = lazyAsyncOp.get().whenComplete((r, e) -> {
                trackedFutures.remove(holder);
                afterOp.accept(r, e);

                if (e != null) {
                    getStatusLogger().warn("Error running job {}", holder.jobName, e);
                }
            });
        } catch (Exception e) {
            trackedFutures.remove(holder);
            afterOp.accept(null, e);
            getStatusLogger().error("Error submitting job {} to executor {}", holder.jobName, executor(), e);
        }
    }

    private void runAsyncTracked(String jobName, Runnable op, BiConsumer<Void, Throwable> afterOp) {
        runAsyncTracked(jobName, (Supplier<CompletableFuture<Void>>) () -> CompletableFuture.runAsync(op, executor()), afterOp);
    }

    private void runAsyncTracked(String jobName, Runnable op, Runnable afterOp) {
        runAsyncTracked(jobName, op, (ignore1, ignore2) -> afterOp.run());
    }

    private boolean isSeparatorNeeded(byte[] left, byte[] right) {
        return switch (batchSeparatorInsertionStrategy) {
            case ALWAYS -> true;
            case IF_MISSING -> !endsWith(left, batchSeparator) && !startsWith(right, batchSeparator);
        };
    }

    @Override
    public String toString() {
        return "AsyncHttpAppender{" +
               "name='" + getName() + '\'' +
               ", state=" + getState() +
               ", url=" + url +
               ", method=" + method +
               '}';
    }

    private static void defaultOnBatchCompletionEventListener(BatchCompletionEvent event) {
        logBatchCompletionEvent(getStatusLogger(), null, event, null);
    }

    public static void logBatchCompletionEvent(Logger log, Marker marker, BatchCompletionEvent event, Executor executor) {
        Logger actualLog;
        if (!(log instanceof StatusLogger) && !event.source.isStarted()) {
            actualLog = StatusLogger.getLogger();
        } else {
            actualLog = log;
        }

        Runnable logBatchCompletion = () -> {
            Supplier<String> statStr = () -> {
                double compressionRate = (double) event.stats.batchBytesUncompressed / event.stats.batchBytesEffective;
                return event.stats + " (compressionRate=" + compressionRate +
                       ", maxBatchBufferBytes=" + event.source.maxBatchBufferBytes +
                       ", maxBatchBufferBatches=" + event.source.maxBatchBufferBatches + ")";
            };

            if (event.type instanceof BatchDeliveredError deliveredError) {
                actualLog.warn(marker, () -> new ParameterizedMessage("Error delivering batch {}: {}", statStr.get(), deliveredError.httpStatus));
            } else if (event.type instanceof BatchDeliveryFailed deliveryFailed) {
                actualLog.warn(marker, () -> new ParameterizedMessage("Delivery of batch {} failed", statStr.get(), deliveryFailed.exception));
            } else if (event.type instanceof BatchDeliveredSuccess deliveredSuccess) {
                actualLog.info(marker, () -> new ParameterizedMessage("Delivered batch {} with {}", statStr.get(), deliveredSuccess.httpStatus));
            } else {
                actualLog.error(marker, () -> new ParameterizedMessage("Received event {} with unknown type: {}", statStr.get(), event.type));
            }
        };

        if (executor == null || log instanceof StatusLogger) {
            logBatchCompletion.run();
        } else {
            executor.execute(logBatchCompletion);
        }
    }
}
