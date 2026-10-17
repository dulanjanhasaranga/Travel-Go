package com.travelgo.service;

import com.stripe.exception.StripeException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import com.travelgo.entity.Booking;
import com.travelgo.enums.PaymentType;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Hosted checkout remains opt-in. Configuration checks never contact Stripe. */
@Service
public class StripeService {
    private final String secretKey;
    private final String webhookSecret;
    private final boolean enabled;
    private final boolean simulated;
    private final String paymentMode;
    private final String publicBaseUrl;
    private final Clock clock;
    private final ProviderCheckoutAttemptService attempts;

    public StripeService(@Value("${travelgo.stripe.secret-key:}") String secretKey,
                         @Value("${travelgo.stripe.webhook-secret:}") String webhookSecret,
                         @Value("${travelgo.stripe.enabled:false}") boolean enabled,
                         @Value("${travelgo.payments.simulated:true}") boolean simulated,
                         @Value("${travelgo.stripe.mode:test}") String paymentMode,
                         @Value("${travelgo.public-base-url:http://localhost:8081}") String publicBaseUrl,
                         Clock clock, ProviderCheckoutAttemptService attempts) {
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
        this.enabled = enabled;
        this.simulated = simulated;
        this.paymentMode = paymentMode;
        this.publicBaseUrl = publicBaseUrl;
        this.clock = clock;
        this.attempts = attempts;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (!enabled) return;
        if (simulated) throw new IllegalStateException("Choose either simulated payments or Stripe checkout, not both.");
        if (!"test".equals(paymentMode) && !"live".equals(paymentMode))
            throw new IllegalStateException("Stripe mode must be explicitly test or live.");
        if (secretKey == null || !secretKey.matches("sk_" + paymentMode + "_[A-Za-z0-9]+")
                || secretKey.contains("placeholder"))
            throw new IllegalStateException("The Stripe secret key must match the selected payment mode.");
        if (webhookSecret == null || !webhookSecret.matches("whsec_[A-Za-z0-9]+")
                || webhookSecret.contains("placeholder"))
            throw new IllegalStateException("A Stripe webhook signing secret is required before checkout can be enabled.");
        URI origin = origin();
        if (!"https".equals(origin.getScheme()) && (isLiveMode() || !isLoopback(origin.getHost())))
            throw new IllegalStateException("Stripe requires an HTTPS public origin; loopback HTTP is allowed only for test mode.");
    }

    public boolean isConfigured() { return enabled && !simulated; }
    public boolean isLiveMode() { return "live".equals(paymentMode); }
    public String getPaymentMode() { return paymentMode; }
    public String getWebhookSecret() { return webhookSecret; }

    public Event verifyWebhook(String payload, String signature) throws SignatureVerificationException {
        requireConfigured();
        return Webhook.constructEvent(payload, signature, webhookSecret);
    }

    public void validateEvent(Event event) {
        requireConfigured();
        if (event == null || event.getId() == null || !event.getId().matches("evt_[A-Za-z0-9]+")
                || event.getLivemode() == null || event.getLivemode() != isLiveMode()
                || event.getAccount() != null)
            throw new IllegalArgumentException("Unexpected Stripe account, event or payment mode.");
    }

    public String createCheckoutSession(Booking booking, PaymentType type, BigDecimal amount,
                                        LocalDateTime paymentDeadline, String successUrl, String cancelUrl) throws StripeException {
        requireConfigured();
        requireCharge(booking, type, amount);
        requireCallback(successUrl);
        requireCallback(cancelUrl);
        if (paymentDeadline == null) throw new IllegalStateException("The payment deadline is unavailable. Contact the visa officer.");
        long deadline = paymentDeadline.atZone(clock.getZone()).toEpochSecond();
        long now = clock.instant().getEpochSecond();
        var attempt = attempts.prepare(booking, type, amount, paymentDeadline, paymentMode, deploymentReference());
        if (attempt.getCheckoutUrl() != null) { validateCheckoutAddress(attempt.getCheckoutUrl()); return attempt.getCheckoutUrl(); }
        long expiresAt = attempt.getExpiresAt();
        // A retry uses the original expiry, even if the provider call failed or its response was lost.
        if (expiresAt < now + 1800)
            throw new IllegalStateException("The prepared checkout is too close to expiry. Contact the visa officer.");
        long amountMinor = minorUnits(amount);
        String quote = quoteReference(booking, type, amountMinor, deadline);
        Map<String, String> metadata = Map.of(
                "application", deploymentReference(), "schema", "1", "paymentMode", paymentMode,
                "bookingId", booking.getId().toString(), "customerId", booking.getUser().getId().toString(),
                "paymentType", type.name(), "amountMinor", Long.toString(amountMinor),
                "quoteDeadline", Long.toString(deadline), "quoteReference", quote, "checkoutAttemptId", attempt.getId().toString());
        String product = "TravelGO — " + type.name().replace('_', ' ') + " — Booking #" + booking.getId();
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .setClientReferenceId(quote).setExpiresAt(expiresAt)
                .setSuccessUrl(successUrl + (successUrl.contains("?") ? "&" : "?") + "session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(cancelUrl)
                .putAllMetadata(metadata)
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder().putAllMetadata(metadata).build())
                .addLineItem(SessionCreateParams.LineItem.builder().setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder().setCurrency("usd")
                                .setUnitAmount(amountMinor)
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder().setName(product).build())
                                .build()).build()).build();
        // The same booking/amount/deadline retry reuses one Stripe checkout operation.
        Session session = createSession(params, options(quote + "-" + attempt.getId()));
        if (session == null || session.getLivemode() == null || session.getLivemode() != isLiveMode()
                || session.getId() == null || !session.getId().matches("cs_" + paymentMode + "_[A-Za-z0-9]+")
                || !quote.equals(session.getClientReferenceId()) || session.getUrl() == null
                || !"payment".equals(session.getMode()) || !"usd".equals(session.getCurrency())
                || session.getAmountTotal() == null || session.getAmountTotal() != amountMinor
                || session.getExpiresAt() == null || session.getExpiresAt() != expiresAt
                || session.getMetadata() == null || !session.getMetadata().entrySet().containsAll(metadata.entrySet()))
            throw new IllegalStateException("The payment provider returned an unexpected checkout. Contact the visa officer.");
        validateCheckoutAddress(session.getUrl());
        attempts.completed(attempt.getId(), session.getId(), session.getUrl());
        return session.getUrl();
    }

    /** Validate provider evidence before the existing locked business operation records payment. */
    public void validateCompletedSession(Session session, Booking booking, PaymentType type, BigDecimal expected) {
        requireConfigured();
        requireCharge(booking, type, expected);
        long amountMinor = minorUnits(expected);
        if (session == null || session.getId() == null || !session.getId().matches("cs_" + paymentMode + "_[A-Za-z0-9]+")
                || !"payment".equals(session.getMode()) || !"complete".equals(session.getStatus())
                || !"paid".equals(session.getPaymentStatus()) || !"usd".equals(session.getCurrency())
                || session.getLivemode() == null || session.getLivemode() != isLiveMode()
                || session.getAmountTotal() == null || session.getAmountTotal() != amountMinor
                || session.getPaymentIntent() == null || !session.getPaymentIntent().matches("pi_[A-Za-z0-9]+"))
            throw new IllegalArgumentException("Unexpected Stripe session, status, currency, amount or payment mode.");
        Map<String, String> metadata = session.getMetadata();
        if (metadata == null || !deploymentReference().equals(metadata.get("application"))
                || !"1".equals(metadata.get("schema")) || !paymentMode.equals(metadata.get("paymentMode"))
                || !booking.getId().toString().equals(metadata.get("bookingId"))
                || !booking.getUser().getId().toString().equals(metadata.get("customerId"))
                || !type.name().equals(metadata.get("paymentType"))
                || !Long.toString(amountMinor).equals(metadata.get("amountMinor")))
            throw new IllegalArgumentException("Stripe checkout does not match this booking and quote.");
        long deadline;
        try { deadline = Long.parseLong(metadata.get("quoteDeadline")); }
        catch (RuntimeException invalidDeadline) { throw new IllegalArgumentException("Stripe quote deadline is invalid."); }
        String quote = quoteReference(booking, type, amountMinor, deadline);
        if (!quote.equals(metadata.get("quoteReference")) || !quote.equals(session.getClientReferenceId())
                || session.getCreated() == null || session.getExpiresAt() == null
                || session.getCreated() >= deadline || session.getExpiresAt() > deadline
                || session.getExpiresAt() <= session.getCreated())
            throw new IllegalArgumentException("Stripe checkout does not match its recorded quote deadline.");
        Long attemptId;
        try { attemptId = Long.valueOf(metadata.get("checkoutAttemptId")); }
        catch (RuntimeException invalidAttempt) { throw new IllegalArgumentException("Stripe checkout attempt is invalid."); }
        attempts.verify(attemptId, booking, type, expected, deadline, paymentMode, deploymentReference(), session.getId(), session.getExpiresAt());
    }

    public Session verifySession(String sessionId) throws StripeException {
        requireConfigured();
        if (sessionId == null || !sessionId.matches("cs_" + paymentMode + "_[A-Za-z0-9]+"))
            throw new IllegalArgumentException("Invalid Stripe session reference.");
        return Session.retrieve(sessionId, options(null));
    }

    protected Session createSession(SessionCreateParams params, RequestOptions options) throws StripeException {
        return Session.create(params, options);
    }

    private RequestOptions options(String idempotencyKey) {
        return RequestOptions.builder().setApiKey(secretKey).setIdempotencyKey(idempotencyKey).build();
    }
    private void requireConfigured() {
        if (!isConfigured()) throw new IllegalStateException("Stripe checkout is disabled. No provider payment was recorded.");
        validateConfiguration();
    }
    private void requireCharge(Booking booking, PaymentType type, BigDecimal amount) {
        if (booking == null || booking.getId() == null || booking.getUser() == null || booking.getUser().getId() == null
                || (type != PaymentType.VISA_DOCUMENTATION && type != PaymentType.FULL_PACKAGE))
            throw new IllegalArgumentException("A current booking, customer and supported payment type are required.");
        minorUnits(amount);
    }
    private long minorUnits(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("The server payment quote must be positive with at most two decimal places.");
        try { return amount.movePointRight(2).longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("The server payment quote is invalid."); }
    }
    private String quoteReference(Booking booking, PaymentType type, long amountMinor, long deadline) {
        return "travelgo-" + hash(deploymentReference() + ":" + paymentMode + ":" + booking.getId() + ":"
                + booking.getUser().getId() + ":" + type.name() + ":" + amountMinor + ":" + deadline);
    }
    private String deploymentReference() { return "travelgo-" + hash(origin().toString()).substring(0, 24); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable.", impossible); }
    }
    private URI origin() {
        try {
            URI uri = URI.create(publicBaseUrl);
            if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) throw new IllegalArgumentException();
            return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
        } catch (IllegalArgumentException invalid) { throw new IllegalStateException("Configure a valid public origin before enabling Stripe."); }
    }
    private void requireCallback(String url) {
        try {
            URI callback = URI.create(url);
            URI origin = origin();
            if (!origin.getScheme().equals(callback.getScheme()) || !origin.getRawAuthority().equals(callback.getRawAuthority())
                    || callback.getRawUserInfo() != null || callback.getRawFragment() != null
                    || !callback.getPath().matches("/customer/payments/checkout/[1-9][0-9]*")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("The Stripe return address must belong to this application."); }
    }
    private boolean isLoopback(String host) { return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host); }
    private void validateCheckoutAddress(String url) {
        URI checkout = URI.create(url);
        if (!"https".equals(checkout.getScheme()) || !"checkout.stripe.com".equals(checkout.getHost())
                || checkout.getRawUserInfo() != null || checkout.getPort() != -1)
            throw new IllegalStateException("The payment provider returned an unexpected checkout address.");
    }
}
