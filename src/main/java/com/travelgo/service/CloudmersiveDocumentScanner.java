package com.travelgo.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Optional academic-cloud scan. The caller must enforce the guarded evaluation profile.
 * Protocol: https://api.cloudmersive.com/docs/virus.asp
 * Free plan: https://www.cloudmersive.com/pricing-small-business
 * Admission is per application instance; the provider enforces the monthly account quota.
 */
public final class CloudmersiveDocumentScanner {
    public static final long MAX_DOCUMENT_BYTES = 3_000_000L;
    static final URI ENDPOINT = URI.create("https://api.cloudmersive.com/virus/scan/file");
    static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private static final long MIN_REQUEST_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final String FAILURE = "This document could not pass the security check. Please try again later or contact your visa officer.";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(2048).maxNameLength(128)
                    .maxNumberLength(32).maxDocumentLength(MAX_RESPONSE_BYTES).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String apiKey;
    private final Duration requestTimeout;
    private final long maxBytes;
    private final Connector connector;
    private final LongSupplier nanoTime;
    private final AtomicBoolean busy = new AtomicBoolean();
    // Only the admitted caller accesses the request-start state.
    private boolean requested;
    private long lastRequestStart;

    public CloudmersiveDocumentScanner(String apiKey, int connectTimeoutMillis, int requestTimeoutMillis, long maxBytes) {
        this(apiKey, connectTimeoutMillis, requestTimeoutMillis, maxBytes, null, System::nanoTime);
    }

    CloudmersiveDocumentScanner(String apiKey, int connectTimeoutMillis, int requestTimeoutMillis,
            long maxBytes, Connector connector, LongSupplier nanoTime) {
        validateConfiguration(apiKey, connectTimeoutMillis, requestTimeoutMillis, maxBytes);
        this.apiKey = apiKey;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMillis);
        this.maxBytes = maxBytes;
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.connector = connector == null ? new HttpsConnector(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER).build()) : connector;
    }

    /** Configuration-only: never authenticates a credential or connects to the provider. */
    public static void validateConfiguration(String apiKey, int connectTimeoutMillis, int requestTimeoutMillis, long maxBytes) {
        if (apiKey == null || !apiKey.matches("[A-Za-z0-9_-]{16,256}")
                || connectTimeoutMillis < 500 || connectTimeoutMillis > 10000
                || requestTimeoutMillis < 1000 || requestTimeoutMillis > 30000
                || requestTimeoutMillis < connectTimeoutMillis || maxBytes < 1 || maxBytes > MAX_DOCUMENT_BYTES)
            throw new IllegalArgumentException("Cloud document scanner settings require a credential and bounded timeouts and size.");
    }

    /** Immediate admission failure avoids tying up servlet workers while rate-limited or busy. No retries. */
    public void scan(byte[] content) throws IOException {
        if (content == null || content.length == 0 || content.length > maxBytes || !busy.compareAndSet(false, true))
            throw unavailable();
        try {
            if (Thread.currentThread().isInterrupted()) throw unavailable();
            long now = nanoTime.getAsLong();
            if (requested && now - lastRequestStart < MIN_REQUEST_INTERVAL_NANOS) throw unavailable();
            HttpRequest request = request(content);
            lastRequestStart = nanoTime.getAsLong();
            requested = true;
            Response response = connector.execute(request, MAX_RESPONSE_BYTES);
            if (response == null || response.status() != 200 || response.body() == null
                    || response.body().length > MAX_RESPONSE_BYTES) throw unavailable();
            JsonNode body = JSON.readTree(response.body());
            if (body == null || !body.isObject() || body.size() != 2 || !body.path("CleanResult").isBoolean()
                    || !body.path("CleanResult").booleanValue() || !body.has("FoundViruses")) throw unavailable();
            JsonNode viruses = body.get("FoundViruses");
            if (!viruses.isNull() && !(viruses.isArray() && viruses.isEmpty())) throw unavailable();
        } catch (IOException | RuntimeException failure) {
            // Responses, signatures, credentials, file names and transport causes remain private.
            throw unavailable();
        } finally { busy.set(false); }
    }

    private HttpRequest request(byte[] content) {
        String boundary = "TravelGO" + UUID.randomUUID().toString().replace("-", "");
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"inputFile\"; filename=\"document.bin\""
                + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        return HttpRequest.newBuilder(ENDPOINT).timeout(requestTimeout)
                .header("Apikey", apiKey).header("Accept", "application/json").header("Accept-Encoding", "identity")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofByteArray(content), HttpRequest.BodyPublishers.ofByteArray(suffix)))
                .build();
    }

    private static IOException unavailable() { return new IOException(FAILURE); }

    @FunctionalInterface interface Connector {
        Response execute(HttpRequest request, int responseLimit) throws IOException;
    }
    record Response(int status, byte[] body) {}

    /** One reusable JDK client; deadline includes receiving the whole body, with no extra worker executor. */
    static final class HttpsConnector implements Connector {
        private final HttpClient client;
        HttpsConnector(HttpClient client) {
            if (client.followRedirects() != HttpClient.Redirect.NEVER)
                throw new IllegalArgumentException("Cloud scanner redirects must be disabled.");
            this.client = client;
        }
        @Override public Response execute(HttpRequest request, int responseLimit) throws IOException {
            if (!ENDPOINT.equals(request.uri()) || !"POST".equals(request.method()) || responseLimit != MAX_RESPONSE_BYTES)
                throw unavailable();
            CompletableFuture<HttpResponse<byte[]>> pending = null;
            try {
                long timeoutNanos = request.timeout().orElseThrow().toNanos();
                long started = System.nanoTime();
                pending = client.sendAsync(request, ignored -> new LimitedBody(responseLimit));
                long remaining = timeoutNanos - (System.nanoTime() - started);
                if (remaining <= 0) throw unavailable();
                HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
                return new Response(response.statusCode(), response.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (Exception failure) { throw unavailable(); }
            finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
        }
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) {
            if (limit != MAX_RESPONSE_BYTES) throw new IllegalArgumentException("Cloud scanner response bound is invalid.");
            this.limit = limit;
        }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (result.isDone()) return;
            for (ByteBuffer chunk : chunks) {
                int remaining = chunk.remaining();
                if (remaining > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(unavailable()); return;
                }
                byte[] data = new byte[remaining]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(unavailable()); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
