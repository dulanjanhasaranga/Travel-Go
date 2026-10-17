package com.travelgo.service;

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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Fixed provider addresses, bounded requests and no redirects. Credentials never enter a browser form. */
@Service
public class PayHereMerchantClient {
    private final String appId;
    private final String appSecret;
    private final String mode;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public PayHereMerchantClient(@Value("${travelgo.payhere.app-id:}") String appId,
                                @Value("${travelgo.payhere.app-secret:}") String appSecret,
                                @Value("${travelgo.payhere.mode:test}") String mode) {
        this.appId = appId; this.appSecret = appSecret; this.mode = mode;
    }
    public JsonNode payment(String orderId) {
        if (orderId == null || !orderId.matches("TGPH-[1-9][0-9]*-[A-Fa-f0-9]{24}"))
            throw new IllegalArgumentException("Invalid PayHere order reference.");
        return request("GET", "/payment/search?order_id=" + orderId, null, "Bearer " + token(), "application/json");
    }
    /** PayHere documents no idempotency header: callers must commit a durable, single-use claim first. */
    public JsonNode refund(String paymentId, String amount, String description) {
        if (paymentId == null || !paymentId.matches("[0-9]{1,30}")
                || amount == null || !amount.matches("[0-9]{1,8}\\.[0-9]{2}")
                || new java.math.BigDecimal(amount).signum() <= 0)
            throw new IllegalArgumentException("A valid PayHere payment and stored refund amount are required.");
        try {
            return request("POST", "/payment/refund", json.writeValueAsString(Map.of(
                    "payment_id", paymentId, "amount", amount, "description", description)),
                    "Bearer " + token(), "application/json");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("The refund request could not be prepared.");
        }
    }
    private String token() {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()
                || appId.contains(":") || appId.chars().anyMatch(Character::isISOControl)
                || appSecret.chars().anyMatch(Character::isISOControl))
            throw new IllegalStateException("PayHere Merchant API credentials are unavailable.");
        String authorization = "Basic " + Base64.getEncoder().encodeToString((appId + ":" + appSecret).getBytes(StandardCharsets.UTF_8));
        JsonNode result = request("POST", "/oauth/token", "grant_type=client_credentials", authorization, "application/x-www-form-urlencoded");
        String token = result.path("access_token").asText("");
        if (!"bearer".equalsIgnoreCase(result.path("token_type").asText())
                || token.isBlank() || token.length() > 2000 || token.chars().anyMatch(Character::isISOControl)
                || result.path("expires_in").asLong(0) <= 0
                || ("test".equals(mode) && !"SANDBOX".equalsIgnoreCase(result.path("scope").asText())))
            throw new IllegalStateException("PayHere authentication returned an unexpected response.");
        return token;
    }
    private JsonNode request(String method, String path, String body, String authorization, String contentType) {
        if (!"test".equals(mode) && !"live".equals(mode)) throw new IllegalStateException("Choose a valid PayHere mode.");
        URI uri = URI.create(("test".equals(mode) ? "https://sandbox.payhere.lk" : "https://www.payhere.lk") + "/merchant/v1" + path);
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("Authorization", authorization).header("Content-Type", contentType).header("Accept", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            Response response = exchange(request);
            if (response.status() != 200 || response.body() == null || response.body().length() > 200_000)
                throw new IllegalStateException("PayHere could not verify the operation. Review it before retrying.");
            JsonNode parsed = json.readTree(response.body());
            if (parsed == null || !parsed.isObject()) throw new IllegalStateException("PayHere returned an unexpected response.");
            return parsed;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("PayHere verification was interrupted. Review the operation before retrying.");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("PayHere could not verify the operation. Review it before retrying.");
        }
    }
    protected Response exchange(HttpRequest request) throws java.io.IOException, InterruptedException {
        var subscriber = new BoundedBodySubscriber(200_000);
        var pending = http.sendAsync(request, ignored -> subscriber);
        HttpResponse<String> result = awaitResponse(pending, subscriber, Duration.ofSeconds(20));
        return new Response(result.statusCode(), result.body());
    }

    /** The future completes only after this subscriber receives the entire bounded body. */
    static HttpResponse<String> awaitResponse(CompletableFuture<HttpResponse<String>> pending,
            BoundedBodySubscriber subscriber, Duration deadline) throws IOException, InterruptedException {
        try {
            return pending.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            subscriber.abort(new IOException("Provider response exceeded the total request deadline."));
            pending.cancel(true);
            throw new IOException("Provider response exceeded the total request deadline.");
        } catch (InterruptedException interrupted) {
            subscriber.abort(new IOException("Provider response was interrupted."));
            pending.cancel(true);
            throw interrupted;
        } catch (ExecutionException failure) {
            subscriber.abort(new IOException("Provider response could not be completed."));
            pending.cancel(true);
            throw new IOException("Provider response could not be completed.");
        }
    }

    /** Cancel oversized or stalled responses without first buffering an unlimited body. */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<String> completed = new CompletableFuture<>();
        private Flow.Subscription subscription;

        BoundedBodySubscriber(int limit) { this.limit = limit; }
        @Override public CompletionStage<String> getBody() { return completed; }
        @Override public synchronized void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null || completed.isDone()) { incoming.cancel(); return; }
            subscription = incoming;
            incoming.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> chunks) {
            if (completed.isDone()) return;
            long size = bytes.size();
            for (ByteBuffer chunk : chunks) size += chunk.remaining();
            if (size > limit) { abort(new IOException("Provider response exceeds the accepted size.")); return; }
            for (ByteBuffer chunk : chunks) {
                byte[] data = new byte[chunk.remaining()];
                chunk.get(data);
                bytes.write(data, 0, data.length);
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable failure) { abort(failure); }
        @Override public synchronized void onComplete() {
            if (!completed.isDone()) completed.complete(bytes.toString(StandardCharsets.UTF_8));
            bytes.reset();
        }
        synchronized void abort(Throwable failure) {
            if (!completed.completeExceptionally(failure)) return;
            bytes.reset();
            if (subscription != null) subscription.cancel();
        }
    }
    protected record Response(int status, String body) { }
}
