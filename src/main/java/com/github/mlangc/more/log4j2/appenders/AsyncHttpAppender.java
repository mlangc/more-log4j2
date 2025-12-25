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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AbstractManager;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.*;
import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;
import org.apache.logging.log4j.core.util.Log4jThreadFactory;
import org.apache.logging.log4j.core.util.NanoClock;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;

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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import static java.util.Objects.requireNonNull;

@Plugin(name = "AsyncHttp", category = Node.CATEGORY, elementType = Appender.ELEMENT_TYPE, printObject = true)
public class AsyncHttpAppender extends AbstractAppender {
    private static final String DROPPING_BATCH_STATUS_LOG_MARKER_PREFIX = "[DROPPING_BATCH_MARKER]";
    private static final long DEFAULT_TIMEOUT_SECS = 15;
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private final URI url;
    private final int maxBatchBytes;
    private final int lingerMs;
    private final long lingerNs;
    private final int maxInFlight;
    private final int maxBatchLogEvents;
    private final int maxBatchBufferBytes;
    private final HttpClientManager httpClientManager;
    private final NanoClock ticker;
    private final Configuration configuration;
    private final RequestMethod method;
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

    private final Set<FutureHolder> trackedFutures = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Lock lock = new ReentrantLock();
    private final Semaphore allowedInFlight;
    private final List<byte[]> currentBatch = new ArrayList<>();
    private long currentBatchBytes;
    private final Deque<Batch> bufferedBatches;
    private long currentBufferBytes;
    private long firstRecordNanos;
    private ScheduledFuture<?> scheduledFlush;

    private record Batch(byte[] data, long uncompressedBytes, int logEvents) {
        int effectiveBytes() {
            return data.length;
        }
    }

    private static class FutureHolder {
        final String jobName;
        CompletableFuture<?> future;

        FutureHolder(String jobName) {
            this.jobName = jobName;
        }
    }

    public static StatusListener newDroppedBatchListener(Runnable onBatchDropped) {
        return new StatusListener() {
            @Override
            public void log(StatusData data) {
                if (data.getMessage().getFormattedMessage().startsWith(DROPPING_BATCH_STATUS_LOG_MARKER_PREFIX)) {
                    onBatchDropped.run();
                }
            }

            @Override
            public Level getStatusLevel() {
                return Level.WARN;
            }

            @Override
            public void close() {

            }
        };
    }

    public sealed interface BatchCompletionType { }

    public record BatchDeliveredSuccess(HttpStatus httpStatus, int tries) implements BatchCompletionType { }

    public record BatchDeliveredError(HttpStatus httpStatus, int tries) implements BatchCompletionType { }

    public record BatchDeliveryFailed(Exception exception, int tries) implements BatchCompletionType { }

    public static final class BatchDropped implements BatchCompletionType {
        private BatchDropped() {

        }
    }

    public static final BatchDropped BATCH_DROPPED = new BatchDropped();

    public record BatchCompletionEvent(AsyncHttpAppender source, long bytesUncompressed, int bytesEffective, int logEvents, BatchCompletionType completionType) {
        private BatchCompletionEvent(AsyncHttpAppender source, Batch batch, BatchCompletionType completionType) {
            this(source, batch.uncompressedBytes, batch.effectiveBytes(), batch.logEvents, completionType);
        }
    }

    public interface BatchCompletionListener {
        void onBatchCompletionEvent(BatchCompletionEvent completionEvent);
    }

    public enum RequestMethod {
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
            int maxBatchBytes, int lingerMs, int maxInFlight,
            int maxBatchLogEvents, HttpClientManager httpClientManager, NanoClock ticker, Configuration configuration, RequestMethod method,
            byte[] batchPrefix, byte[] batchSeparator, byte[] batchSuffix, int retries, ContentEncoding contentEncoding, int maxBatchBufferBytes, int[] httpSuccessCodes, int[] httpRetryCodes,
            boolean retryOnIoError, BatchSeparatorInsertionStrategy batchSeparatorInsertionStrategy, String batchCompletionListener) {
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

        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must not be smaller than 1, but got " + maxInFlight);
        }

        if (intersects(httpSuccessCodes, httpRetryCodes)) {
            throw new IllegalArgumentException(
                    "httpSuccessCodes=" + Arrays.toString(httpSuccessCodes) + " and httpRetryCodes=" + Arrays.toString(httpRetryCodes) + " must not intersect");
        }

        this.url = requireNonNull(url);
        this.maxBatchBytes = maxBatchBytes;
        this.maxBatchBufferBytes = maxBatchBufferBytes;
        this.lingerMs = lingerMs;
        this.lingerNs = TimeUnit.MILLISECONDS.toNanos(lingerMs);
        this.maxInFlight = maxInFlight;
        this.allowedInFlight = new Semaphore(maxInFlight);
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
                2 * lingerMs,
                s -> contains(s, httpSuccessCodes),
                retryOnIoError ? e -> e instanceof IOException : e -> false,
                s -> contains(s, httpRetryCodes));

        this.retryManager = new HttpRetryManager(retryConfig, httpClientManager.executor);

        if (batchCompletionListener != null && !batchCompletionListener.isEmpty()) {
            this.batchCompletionListener = newInstanceFromConfiguredClassName(BatchCompletionListener.class, batchCompletionListener);
        } else {
            this.batchCompletionListener = AsyncHttpAppender::defaultOnBatchCompletionEventListener;
        }
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
            @PluginAttribute(value = "maxBatchLogEvents", defaultInt = 1000) int maxBatchLogEvents,
            @PluginAttribute(value = "ignoreExceptions", defaultBoolean = true) boolean ignoreExceptions,
            @PluginAttribute(value = "connectTimeoutMillis", defaultInt = 10_000) int connectTimeoutMillis,
            @PluginAttribute(value = "readTimeoutMillis", defaultInt = 10_000) int readTimeoutMillis,
            @PluginAttribute(value = "maxInFlight", defaultInt = 5) int maxInFlight,
            @PluginAttribute(value = "method", defaultString = "POST") RequestMethod method,
            @PluginAttribute(value = "batchPrefix") String batchPrefix,
            @PluginAttribute(value = "batchSeparator") String batchSeparator,
            @PluginAttribute(value = "batchSuffix") String batchSuffix,
            @PluginAttribute(value = "batchSeparatorInsertionStrategy", defaultString = "if_missing") BatchSeparatorInsertionStrategy batchSeparatorInsertionStrategy,
            @PluginAttribute(value = "retries", defaultInt = 5) int retries,
            @PluginAttribute(value = "httpSuccessCodes", defaultString = "200,202,204") String httpSuccessCodes,
            @PluginAttribute(value = "httpRetryCodes", defaultString = "500,502,503,504") String httpRetryCodes,
            @PluginAttribute(value = "retryOnIoError", defaultBoolean = true) boolean retryOnIoError,
            @PluginAttribute(value = "contentEncoding", defaultString = "identity") ContentEncoding contentEncoding,
            @PluginAttribute(value = "httpClientSslConfigSupplier") String httpClientSslConfigSupplier,
            @PluginAttribute(value = "batchCompletionListener") String batchCompletionListener,
            @PluginElement("Filter") Filter filter,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginElement("Properties") Property[] properties,
            @PluginConfiguration Configuration configuration) {
        var managerData = new HttpClientManagerData(Duration.ofMillis(connectTimeoutMillis), Duration.ofMillis(readTimeoutMillis), httpClientSslConfigSupplier);

        if (batchSeparator == null) {
            batchSeparator = "\n";
        }

        return new AsyncHttpAppender(name, url, filter, layout, ignoreExceptions, properties,
                maxBatchBytes, lingerMs, maxInFlight, maxBatchLogEvents,
                HttpClientManager.get(managerData), System::nanoTime, configuration, method,
                batchPrefix == null ? EMPTY_BYTE_ARRAY : batchPrefix.getBytes(StandardCharsets.UTF_8),
                batchSeparator.getBytes(StandardCharsets.UTF_8),
                batchSuffix == null ? EMPTY_BYTE_ARRAY : batchSuffix.getBytes(StandardCharsets.UTF_8),
                retries, contentEncoding,
                maxBatchBufferBytes == Integer.MIN_VALUE
                        ? (maxBatchBytes > 0 ? clampedMul(maxBatchBytes, 50) : 250_000_000)
                        : maxBatchBufferBytes,
                HttpHelpers.parseHttpStatusCodes(httpSuccessCodes),
                HttpHelpers.parseHttpStatusCodes(httpRetryCodes),
                retryOnIoError, batchSeparatorInsertionStrategy, batchCompletionListener);
    }

    private static int clampedMul(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) a * b);
    }

    @Override
    public void append(LogEvent event) {
        byte[] eventBytes = getLayout().toByteArray(event);
        doWithLock(() -> {
            if (currentBatch.isEmpty()) {
                firstRecordNanos = ticker.nanoTime();

                if (scheduledFlush != null) {
                    scheduledFlush.cancel(false);
                }

                scheduledFlush = executor().schedule(this::flushIfLingerElapsed, lingerMs, TimeUnit.MILLISECONDS);
            }

            if (needsFlushAssumeLocked(eventBytes)) {
                flushAssumingLocked();
            }

            if (currentBatchBytes == 0) {
                currentBatchBytes += batchPrefix.length;
            }

            currentBatch.add(eventBytes);
            currentBatchBytes += eventBytes.length;
        });
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
                flushAssumingLocked();
            }
        });
    }

    private void flushAssumingLocked() {
        if (currentBatch.isEmpty()) {
            return;
        }

        // Implementation note: Creating a batch can be a relatively expensive operation, especially if compression is enabled.
        // It might therefore be preferable to release the lock before doing so, at least under specific conditions.
        Batch batch = createBatch(currentBatch);
        if (batch.effectiveBytes() + currentBufferBytes <= maxBatchBufferBytes || currentBufferBytes == 0) {
            currentBufferBytes += batch.effectiveBytes();
            bufferedBatches.addLast(batch);
            runAsyncTracked("drainBufferedBatches", this::drainBufferedBatches, () -> { });
        } else {
            batchCompletionListener.onBatchCompletionEvent(new BatchCompletionEvent(this, batch, BATCH_DROPPED));
        }

        currentBatch.clear();
        currentBatchBytes = 0;
    }

    private void drainBufferedBatches() {
        doWithLock(() -> {
            while (true) {
                var oldestBatch = bufferedBatches.peekFirst();
                if (oldestBatch == null) {
                    break;
                }

                if (!allowedInFlight.tryAcquire()) {
                    executor().schedule(this::drainBufferedBatches, ThreadLocalRandom.current().nextLong(lingerNs + 1), TimeUnit.NANOSECONDS);
                    getStatusLogger().debug("Too many request in flight; trying later");
                    break;
                }

                bufferedBatches.removeFirst();
                int releaseBytes = oldestBatch.data.length;

                runAsyncTracked("sendBatchBytes", () -> sendBatchAndEventuallyRetry(oldestBatch), () -> {
                    allowedInFlight.release();
                    currentBufferBytes -= releaseBytes;
                });
            }
        });
    }

    private CompletableFuture<Void> sendBatchAndEventuallyRetry(Batch batch) {
        return retryManager.run(() -> sendBatch(batch)).whenComplete((response, throwable) -> {
            BatchCompletionType completionType = null;
            if (response != null) {
                completionType = new BatchDeliveredSuccess(response.status(), response.tries());
            } else if (throwable instanceof HttpErrorResponseException errorResponseException) {
                completionType = new BatchDeliveredError(errorResponseException.httpStatus(), errorResponseException.tries());
            } else if (throwable instanceof HttpRetryManagerException retryManagerException) {
                completionType = new BatchDeliveryFailed(retryManagerException, retryManagerException.tries());
            } else if (throwable instanceof Exception exception) {
                completionType = new BatchDeliveryFailed(exception, -1);
            }

            if (completionType != null) {
                batchCompletionListener.onBatchCompletionEvent(new BatchCompletionEvent(this, batch, completionType));
            }
        }).thenApply(ignore -> null);
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

        for (int i = 0; i < pattern.length; i++) {
            if (data[data.length - pattern.length + i] != pattern[i]) {
                return false;
            }
        }

        return true;
    }

    private static boolean startsWith(byte[] data, byte[] pattern) {
        if (pattern.length > data.length) {
            return false;
        }

        for (int i = 0; i < pattern.length; i++) {
            if (data[i] != pattern[i]) {
                return false;
            }
        }

        return true;
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
        return lingerMs;
    }

    int maxBatchBytes() {
        return maxBatchBytes;
    }

    int maxBatchBufferBytes() {
        return maxBatchBufferBytes;
    }

    int connectTimeoutMillis() {
        return Math.toIntExact(httpClientManager.httpClient.connectTimeout().orElseThrow().toMillis());
    }

    int readTimeoutMillis() {
        return Math.toIntExact(httpClientManager.readTimeout.toMillis());
    }

    int maxInFlight() {
        return maxInFlight;
    }

    int maxBatchLogEvents() {
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

    RequestMethod method() {
        return method;
    }

    BatchSeparatorInsertionStrategy batchSeparatorInsertionStrategy() {
        return batchSeparatorInsertionStrategy;
    }

    String httpClientSslConfigSupplier() {
        return httpClientManager.httpClientSslConfigSupplier;
    }

    private Batch createBatch(List<byte[]> events) {
        byte[] res = EMPTY_BYTE_ARRAY;
        if (!events.isEmpty()) {
            int totalSize = batchPrefix.length + batchSuffix.length;
            totalSize += events.get(0).length;

            for (int i = 1; i < events.size(); i++) {
                if (isSeparatorNeeded(events.get(i - 1), events.get(i))) {
                    totalSize += batchSeparator.length;
                }

                totalSize += events.get(i).length;
            }

            res = new byte[totalSize];
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
        }

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
            timeout = DEFAULT_TIMEOUT_SECS;
            timeUnit = java.util.concurrent.TimeUnit.SECONDS;
        }

        setStopping();
        doWithLock(() -> {
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }

            flushAssumingLocked();
        });

        var stoppedCleanly = true;
        try {
            var waitBeforeDisablingRetriesMillis = timeUnit.toMillis(timeout) - Math.round(retryManager.config.maxDelayMillis() * 1.1);

            if (waitBeforeDisablingRetriesMillis <= 0) {
                retryManager.disableRetries();
            } else {
                executor().schedule(retryManager::disableRetries, waitBeforeDisablingRetriesMillis, TimeUnit.MILLISECONDS);
            }

            var remainingNanos = timeUnit.toNanos(timeout);
            while (remainingNanos > 0 && hasUnpublishedLogData()) {
                var t0 = ticker.nanoTime();

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
            // Without this, retries might be kept enabled in the case where we scheduled disableRetries above, but shoutdown the executor before the schedule runs below.
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
        return trackedFutures.stream().map(t -> t.future)
                .filter(Objects::nonNull)
                .toArray(CompletableFuture<?>[]::new);
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

    private void runAsyncTracked(String jobName, Supplier<CompletableFuture<?>> lazyAsyncOp, Runnable afterOp) {
        FutureHolder holder = new FutureHolder(jobName);
        trackedFutures.add(holder);

        try {
            holder.future = lazyAsyncOp.get().whenComplete((ignore, e) -> {
                trackedFutures.remove(holder);
                afterOp.run();

                if (e != null) {
                    getStatusLogger().warn("Error running job {}", holder.jobName, e);
                }
            });
        } catch (Exception e) {
            trackedFutures.remove(holder);
            afterOp.run();
            getStatusLogger().error("Error submitting job {} to executor {}", holder.jobName, executor(), e);
        }
    }

    private void runAsyncTracked(String jobName, Runnable op, Runnable afterOp) {
        runAsyncTracked(jobName, () -> CompletableFuture.runAsync(op, executor()), afterOp);
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
               ", url=" + url +
               ", method=" + method +
               '}';
    }

    private static void defaultOnBatchCompletionEventListener(BatchCompletionEvent event) {
        logBatchCompletionEvent(getStatusLogger(), null, event);
    }

    public static void logBatchCompletionEvent(Logger log, Executor executor, BatchCompletionEvent event) {
        Logger actualLog;
        if (!(log instanceof StatusLogger) && !event.source.isStarted()) {
            actualLog = StatusLogger.getLogger();
        } else {
            actualLog = log;
        }

        Runnable logBatchCompletion = () -> {
            double compressionRate = (double) event.bytesUncompressed / event.bytesEffective;

            if (event.completionType instanceof BatchDeliveredError deliveredError) {
                actualLog.warn("Error delivering batch of {} bytes ({} bytes uncompressed with a compression rate of {}) and {} log events: {}",
                        event.bytesEffective, event.bytesUncompressed, compressionRate, event.logEvents, deliveredError.httpStatus);
            } else if (event.completionType instanceof BatchDeliveryFailed deliveryFailed) {
                actualLog.warn("Delivery of batch with {} bytes ({} bytes uncompressed with a compression rate of {}) and {} log events failed",
                        event.bytesEffective, event.bytesUncompressed, compressionRate, event.logEvents, deliveryFailed.exception);
            } else if (event.completionType instanceof BatchDropped) {
                actualLog.warn("Dropping batch of {} bytes ({} bytes uncompressed with a compression rate of {}) and {} log events because the HTTP backend is not keeping up",
                        event.bytesEffective, event.bytesUncompressed, compressionRate, event.logEvents);
            } else if (event.completionType instanceof BatchDeliveredSuccess deliveredSuccess) {
                actualLog.info("Delivered batch of {} bytes ({} bytes uncompressed with a compression rate of {}) and {} log events with {}",
                        event.bytesEffective, event.bytesUncompressed, compressionRate, event.logEvents, deliveredSuccess.httpStatus);
            } else {
                actualLog.error("Received event with unknown completionType: {}", event);
            }
        };

        if (executor == null || log instanceof StatusLogger) {
            logBatchCompletion.run();
        } else {
            executor.execute(logBatchCompletion);
        }
    }
}
