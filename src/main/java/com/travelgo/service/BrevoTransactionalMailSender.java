package com.travelgo.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelgo.entity.OutboundEmail;
import com.travelgo.util.ProviderMessageIds;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.util.HtmlUtils;

/**
 * Optional HTTPS adapter, retaining JavaMailSender compatibility for account readiness.
 * No request is made at construction or readiness checks. Direct MIME sends fail closed.
 */
public final class BrevoTransactionalMailSender extends JavaMailSenderImpl implements TransactionalMailSender {
    static final URI ENDPOINT = URI.create("https://api.brevo.com/v3/smtp/email");
    static final Duration REQUEST_DEADLINE = Duration.ofSeconds(15);
    static final int MAX_RESPONSE_BYTES = 8192;
    static final int MAX_HTML_BYTES = 262144;
    static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
    static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    private static final Pattern LINK = Pattern.compile("(?is)<a\\b[^>]*\\bhref\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>");
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(1024).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String apiKey;
    private final String publicOrigin;
    private final Exchange exchange;
    private final Duration deadline;

    public BrevoTransactionalMailSender(String apiKey, String publicOrigin) {
        this(apiKey, publicOrigin, defaultExchange(), REQUEST_DEADLINE);
    }

    // Injectable fake exchange and short deadline keep tests entirely offline.
    BrevoTransactionalMailSender(String apiKey, String publicOrigin, Exchange exchange, Duration deadline) {
        this.apiKey = apiKey;
        this.publicOrigin = publicOrigin;
        this.exchange = exchange;
        this.deadline = deadline;
    }

    public static boolean isConfigurationValid(String apiKey, String publicOrigin) {
        if (apiKey == null || !apiKey.matches("[A-Za-z0-9._-]{32,512}")) return false;
        String lowerKey = apiKey.toLowerCase(Locale.ROOT);
        if (lowerKey.startsWith("xsmtpsib-") || lowerKey.contains("placeholder")
                || lowerKey.contains("replace") || lowerKey.contains("changeme")) return false;
        try {
            URI origin = URI.create(publicOrigin);
            String host = origin.getHost();
            return "https".equals(origin.getScheme()) && host != null && host.matches("[A-Za-z0-9.-]+")
                    && !host.equalsIgnoreCase("localhost") && !host.endsWith(".localhost")
                    && !host.startsWith("127.") && !host.equals("0.0.0.0")
                    && origin.getRawUserInfo() == null && origin.getRawQuery() == null && origin.getRawFragment() == null
                    && (origin.getPath().isEmpty() || origin.getPath().equals("/"))
                    && (origin.getPort() == -1 || origin.getPort() == 443);
        } catch (IllegalArgumentException | NullPointerException invalid) { return false; }
    }

    @Override public boolean isConfigured() { return isConfigurationValid(apiKey, publicOrigin); }

    @Override public String sendTransactional(OutboundEmail email, String from) {
        if (!isConfigured()) throw failure();
        CompletableFuture<Response> pending = null;
        try {
            byte[] payload = requestBody(email, from);
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(deadline)
                    .header("api-key", apiKey).header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            pending = exchange.send(request);
            Response response = pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            // A rejection or ambiguous acknowledgement always goes to outbox review.
            // Never log the body, request, key, recipient, account link, or nested exception.
            if (response.status() != 201 || response.body() == null || response.body().length == 0
                    || response.body().length > MAX_RESPONSE_BYTES) throw failure();
            JsonNode result = mapper.readTree(response.body());
            if (result == null || !result.isObject()) throw failure();
            JsonNode messageId = result.get("messageId");
            if (messageId == null || !messageId.isTextual()) throw failure();
            return ProviderMessageIds.normalize(messageId.textValue());
        } catch (InterruptedException interrupted) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure();
        } catch (Exception uncertain) {
            if (pending != null) pending.cancel(true);
            throw failure();
        }
    }

    private byte[] requestBody(OutboundEmail email, String from) throws java.io.IOException {
        if (email == null || !address(from) || !address(email.getRecipient()) || email.getSubject() == null
                || email.getSubject().isBlank() || email.getSubject().length() > 255
                || email.getSubject().chars().anyMatch(Character::isISOControl)
                || email.getHtmlBody() == null || email.getHtmlBody().isBlank() || email.getHtmlBody().length() > MAX_HTML_BYTES
                || email.getHtmlBody().getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES
                || email.getFeedbackReference() == null || !email.getFeedbackReference().matches("[A-Za-z0-9_-]{43}"))
            throw failure();
        String logoOrigin = publicOrigin.endsWith("/") ? publicOrigin.substring(0, publicOrigin.length() - 1) : publicOrigin;
        String html = email.getHtmlBody().replace("cid:travelgo-logo", logoOrigin + "/images/brand/travelgo-airborne.png");
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("sender", Map.of("email", from, "name", "TravelGO"));
        body.put("to", List.of(Map.of("email", email.getRecipient())));
        body.put("subject", email.getSubject());
        body.put("htmlContent", html);
        body.put("textContent", plainText(html));
        body.put("headers", Map.of("X-Mailin-custom", "travelgo:" + email.getFeedbackReference(),
                "idempotencyKey", claimIdempotencyKey(email.getFeedbackReference())));
        // Idempotency is only duplicate defence for this claim, never permission to resend an uncertain send.
        if (email.getAttachmentData() != null || email.getAttachmentName() != null) {
            String name = email.getAttachmentName();
            byte[] content = email.getAttachmentData();
            if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\")
                    || name.chars().anyMatch(Character::isISOControl) || content == null
                    || content.length == 0 || content.length > MAX_ATTACHMENT_BYTES) throw failure();
            // Brevo's documented wire property is `content`, containing base64 (not a URL).
            body.put("attachment", List.of(Map.of("name", name, "content", Base64.getEncoder().encodeToString(content))));
        }
        byte[] payload = mapper.writeValueAsBytes(body);
        if (payload.length > MAX_REQUEST_BYTES) throw failure();
        return payload;
    }

    private static boolean address(String value) {
        return value != null && value.length() <= 150 && value.matches("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static String claimIdempotencyKey(String reference) {
        byte[] random = Base64.getUrlDecoder().decode(reference);
        random[6] = (byte) ((random[6] & 0x0f) | 0x40);
        random[8] = (byte) ((random[8] & 0x3f) | 0x80);
        ByteBuffer bytes = ByteBuffer.wrap(random);
        return new java.util.UUID(bytes.getLong(), bytes.getLong()).toString();
    }

    private static String plainText(String html) {
        String visible = html.replaceAll("(?is)<!--.*?-->|<head\\b.*?</head>|<style\\b.*?</style>|<script\\b.*?</script>", "");
        Matcher links = LINK.matcher(visible);
        StringBuffer withLinks = new StringBuffer();
        while (links.find()) links.appendReplacement(withLinks, Matcher.quoteReplacement(links.group(3) + " (" + links.group(2) + ")"));
        links.appendTail(withLinks);
        return HtmlUtils.htmlUnescape(withLinks.toString().replaceAll("(?i)<br\\s*/?>|</(?:p|h[1-6]|tr|div|table)>", "\n")
                .replaceAll("<[^>]*>", " ")).replace('\u00a0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ").replaceAll(" *\n *", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    @Override protected void doSend(MimeMessage[] messages, Object[] originalMessages) {
        throw new MailSendException("Use durable transactional email delivery for this transport.");
    }

    private static MailSendException failure() {
        return new MailSendException("Transactional email acceptance could not be confirmed; delivery review is required.");
    }

    private static Exchange defaultExchange() {
        HttpClient client = newClient();
        return request -> {
            var sending = client.sendAsync(request, ignored -> new BoundedBodySubscriber());
            var result = sending.thenApply(response -> new Response(response.statusCode(), response.body()));
            result.whenComplete((response, failure) -> { if (result.isCancelled()) sending.cancel(true); });
            return result;
        };
    }

    static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    @FunctionalInterface interface Exchange { CompletableFuture<Response> send(HttpRequest request); }
    record Response(int status, byte[] body) { }

    /** Caps allocation while streaming; the encompassing future also has a wall-clock deadline. */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            if (body.isDone()) return;
            for (ByteBuffer item : items) {
                if (item.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(failure()); return;
                }
                byte[] chunk = new byte[item.remaining()]; item.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { body.completeExceptionally(failure()); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
