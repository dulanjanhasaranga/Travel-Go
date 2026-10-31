package com.travelgo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelgo.entity.OutboundEmail;
import com.travelgo.util.MailConfigurationStatus;
import com.travelgo.util.ProviderMessageIds;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mail.MailSendException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fake exchanges only: no network, real API key, provider account or email recipient. */
@Timeout(5)
class BrevoTransactionalMailSenderTests {
    static final String KEY = "xkeysib-synthetic_offline_test_key_123456789";
    static final String ORIGIN = "https://travelgo.example.test";
    final ObjectMapper mapper = new ObjectMapper();

    OutboundEmail email() {
        var email = new OutboundEmail();
        email.setRecipient("recipient@example.invalid"); email.setSubject("Your TravelGO account");
        email.setHtmlBody("<html><head><style>private-css</style></head><body><img src=\"cid:travelgo-logo\" alt=\"TravelGO\">"
                + "<p>Hello &amp; welcome.</p><a href=\"https://travelgo.example.test/verify?token=synthetic&amp;x=1\">Verify account</a></body></html>");
        email.setFeedbackReference(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        return email;
    }

    BrevoTransactionalMailSender sender(BrevoTransactionalMailSender.Exchange exchange) {
        return new BrevoTransactionalMailSender(KEY, ORIGIN, exchange, BrevoTransactionalMailSender.REQUEST_DEADLINE);
    }

    CompletableFuture<BrevoTransactionalMailSender.Response> response(int status, String body) {
        return CompletableFuture.completedFuture(new BrevoTransactionalMailSender.Response(status, body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void fixedHttpsRequestRetainsBrandingTextLinkAndOpaqueCorrelationWithoutChangingOutboxContent() throws Exception {
        var captured = new AtomicReference<HttpRequest>();
        var sender = sender(request -> { captured.set(request); return response(201, "{\"messageId\":\"<provider-123@relay.test>\"}"); });
        var email = email(); String original = email.getHtmlBody();
        assertEquals("provider-123@relay.test", sender.sendTransactional(email, "approved@example.invalid"));
        HttpRequest request = captured.get();
        assertEquals("https://api.brevo.com/v3/smtp/email", request.uri().toString());
        assertEquals("POST", request.method()); assertEquals(Duration.ofSeconds(15), request.timeout().orElseThrow());
        assertEquals(KEY, request.headers().firstValue("api-key").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
        JsonNode body = mapper.readTree(payload(request));
        assertEquals("approved@example.invalid", body.path("sender").path("email").textValue());
        assertEquals("TravelGO", body.path("sender").path("name").textValue());
        assertEquals("recipient@example.invalid", body.path("to").get(0).path("email").textValue());
        assertEquals(email.getSubject(), body.path("subject").textValue());
        assertTrue(body.path("htmlContent").textValue().contains(ORIGIN + "/images/brand/travelgo-airborne.png"));
        assertFalse(body.path("htmlContent").textValue().contains("cid:"));
        assertTrue(body.path("textContent").textValue().contains("Hello & welcome."));
        assertTrue(body.path("textContent").textValue().contains("https://travelgo.example.test/verify?token=synthetic&x=1"));
        assertFalse(body.path("textContent").textValue().contains("private-css"));
        assertEquals("travelgo:" + email.getFeedbackReference(), body.path("headers").path("X-Mailin-custom").textValue());
        assertEquals(4, UUID.fromString(body.path("headers").path("idempotencyKey").textValue()).version());
        assertEquals(original, email.getHtmlBody());
        assertFalse(body.has("templateId")); assertFalse(body.has("attachment"));
    }

    @Test void attachmentUsesDocumentedBase64ContentAndIdempotencyIsStableOnlyWithinAClaim() throws Exception {
        var captured = new AtomicReference<HttpRequest>();
        var sender = sender(request -> { captured.set(request); return response(201, "{\"messageId\":\"provider-1\"}"); });
        var email = email(); email.setAttachmentName("TravelGO-receipt.pdf");
        email.setAttachmentData("%PDF-synthetic".getBytes(StandardCharsets.UTF_8));
        sender.sendTransactional(email, "approved@example.invalid");
        JsonNode first = mapper.readTree(payload(captured.get()));
        assertEquals("TravelGO-receipt.pdf", first.path("attachment").get(0).path("name").textValue());
        assertArrayEquals(email.getAttachmentData(), Base64.getDecoder().decode(first.path("attachment").get(0).path("content").textValue()));
        assertFalse(first.path("attachment").get(0).has("url")); assertFalse(first.path("attachment").get(0).has("contentBase64"));
        sender.sendTransactional(email, "approved@example.invalid");
        assertEquals(first.path("headers").path("idempotencyKey"), mapper.readTree(payload(captured.get())).path("headers").path("idempotencyKey"));
        byte[] nextReference = new byte[32]; java.util.Arrays.fill(nextReference, (byte) 1);
        email.setFeedbackReference(Base64.getUrlEncoder().withoutPadding().encodeToString(nextReference));
        sender.sendTransactional(email, "approved@example.invalid");
        assertNotEquals(first.path("headers").path("idempotencyKey"), mapper.readTree(payload(captured.get())).path("headers").path("idempotencyKey"));
    }

    @Test void redirectsRejectionsMalformedAmbiguousAndOversizedReceiptsNeverBecomeAcceptance() {
        for (int status : List.of(200, 202, 301, 302, 307, 400, 401, 429, 500, 503))
            assertSanitized(() -> sender(request -> response(status, "{\"messageId\":\"provider-1\",\"secret\":\"sensitive-response\"}"))
                    .sendTransactional(email(), "approved@example.invalid"));
        for (String body : List.of("", "not-json-sensitive-response", "[]", "{}", "{\"messageId\":null}",
                "{\"messageId\":42}", "{\"messageId\":\"\"}", "{\"messageId\":\"<unbalanced\"}",
                "{\"messageId\":\"unsafe\\nvalue\"}", "{\"messageId\":\"two words\"}",
                "{\"messageId\":\"one\",\"messageId\":\"two\"}", "{\"messageId\":\"one\"} {}",
                "{\"messageId\":\"" + "x".repeat(256) + "\"}", "x".repeat(BrevoTransactionalMailSender.MAX_RESPONSE_BYTES + 1)))
            assertSanitized(() -> sender(request -> response(201, body)).sendTransactional(email(), "approved@example.invalid"));
    }

    @Test void unexpectedTransportFailuresAndTimeoutsDoNotLeakCauseAndCancelPendingRequest() {
        assertSanitized(() -> sender(request -> { throw new IllegalStateException(KEY + " sensitive-response"); })
                .sendTransactional(email(), "approved@example.invalid"));
        var pending = new CompletableFuture<BrevoTransactionalMailSender.Response>();
        var shortDeadline = new BrevoTransactionalMailSender(KEY, ORIGIN, request -> pending, Duration.ofMillis(20));
        assertSanitized(() -> shortDeadline.sendTransactional(email(), "approved@example.invalid"));
        assertTrue(pending.isCancelled());
    }

    @Test void interruptedSendCancelsPendingRequestAndRetainsInterruption() {
        var pending = new CompletableFuture<BrevoTransactionalMailSender.Response>();
        try {
            Thread.currentThread().interrupt();
            assertSanitized(() -> sender(request -> pending).sendTransactional(email(), "approved@example.invalid"));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(pending.isCancelled());
        } finally { Thread.interrupted(); }
    }

    @Test void streamingResponseCapsAllocationCancelsOversizeAndDropsSensitiveFailureCause() {
        var subscription = mock(Flow.Subscription.class);
        var body = new BrevoTransactionalMailSender.BoundedBodySubscriber(); body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[BrevoTransactionalMailSender.MAX_RESPONSE_BYTES - 1])));
        body.onNext(List.of(ByteBuffer.wrap(new byte[2])));
        verify(subscription).cancel();
        var failed = assertThrows(java.util.concurrent.CompletionException.class, () -> body.getBody().toCompletableFuture().join());
        assertNull(failed.getCause().getCause()); assertFalse(failed.toString().contains(KEY));
        var complete = new BrevoTransactionalMailSender.BoundedBodySubscriber(); complete.onSubscribe(mock(Flow.Subscription.class));
        complete.onNext(List.of(ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8)))); complete.onComplete();
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), complete.getBody().toCompletableFuture().join());
    }

    @Test void configurationAndDirectMimeReadinessFailClosedWithoutNetwork() {
        var exchange = mock(BrevoTransactionalMailSender.Exchange.class);
        for (String key : List.of("", "short", "placeholder".repeat(4), "xsmtpsib-synthetic_smtp_key_123456789", KEY + "\n")) {
            var sender = new BrevoTransactionalMailSender(key, ORIGIN, exchange, Duration.ofSeconds(1));
            assertFalse(MailConfigurationStatus.isConfigured(true, sender));
            assertSanitized(() -> sender.sendTransactional(email(), "approved@example.invalid"));
        }
        for (String origin : List.of("http://travelgo.example.test", "https://localhost", "https://127.0.0.1", "https://[::1]",
                ORIGIN + "/path", ORIGIN + "?token=secret", "https://user:secret@travelgo.example.test", ORIGIN + "#fragment"))
            assertFalse(BrevoTransactionalMailSender.isConfigurationValid(KEY, origin));
        verifyNoInteractions(exchange);
        var sender = sender(request -> response(201, "{\"messageId\":\"provider-1\"}"));
        assertTrue(MailConfigurationStatus.isConfigured(true, sender));
        assertFalse(MailConfigurationStatus.isConfigured(false, sender));
        assertThrows(MailSendException.class, () -> sender.send(new MimeMessage(Session.getInstance(new Properties()))));
        HttpClient client = BrevoTransactionalMailSender.newClient();
        assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
        assertEquals(Duration.ofSeconds(5), client.connectTimeout().orElseThrow());
    }

    @Test void unsafeOrOversizedContentIsRejectedBeforeTransport() {
        var exchange = mock(BrevoTransactionalMailSender.Exchange.class);
        var sender = new BrevoTransactionalMailSender(KEY, ORIGIN, exchange, Duration.ofSeconds(1));
        var html = email(); html.setHtmlBody("x".repeat(BrevoTransactionalMailSender.MAX_HTML_BYTES + 1));
        var attachment = email(); attachment.setAttachmentName("receipt.pdf");
        attachment.setAttachmentData(new byte[BrevoTransactionalMailSender.MAX_ATTACHMENT_BYTES + 1]);
        var path = email(); path.setAttachmentName("../receipt.pdf"); path.setAttachmentData(new byte[] {1});
        var header = email(); header.setSubject("Subject\r\napi-key: sensitive-response");
        var correlation = email(); correlation.setFeedbackReference("row-id-1");
        for (var email : List.of(html, attachment, path, header, correlation))
            assertSanitized(() -> sender.sendTransactional(email, "approved@example.invalid"));
        verifyNoInteractions(exchange);
    }

    @Test void messageIdentifiersNormalizeOneEnvelopePairOnly() {
        assertEquals("provider-1@relay.test", ProviderMessageIds.normalize("<provider-1@relay.test>"));
        assertTrue(ProviderMessageIds.same("<provider-1@relay.test>", "provider-1@relay.test"));
        assertFalse(ProviderMessageIds.same("<provider-1@relay.test>", "provider-2@relay.test"));
        for (String value : List.of("", "<>", "<<id>>", "<id", "id>", " id ", "id\nvalue", "id value", "ü"))
            assertThrows(IllegalArgumentException.class, () -> ProviderMessageIds.normalize(value));
    }

    void assertSanitized(org.junit.jupiter.api.function.Executable call) {
        MailSendException failure = assertThrows(MailSendException.class, call);
        assertNull(failure.getCause()); assertFalse(failure.toString().contains(KEY));
        assertFalse(failure.toString().contains("sensitive-response"));
        assertFalse(failure.toString().contains("recipient@example.invalid"));
        assertFalse(failure.toString().contains("token=synthetic"));
    }

    byte[] payload(HttpRequest request) throws Exception {
        var bytes = new ByteArrayOutputStream(); var result = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) { byte[] data = new byte[item.remaining()]; item.get(data); bytes.writeBytes(data); }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return result.get(1, TimeUnit.SECONDS);
    }
}
