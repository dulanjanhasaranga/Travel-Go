package com.travelgo;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import com.travelgo.entity.Booking;
import com.travelgo.entity.ProviderCheckoutAttempt;
import com.travelgo.entity.User;
import com.travelgo.enums.PaymentType;
import com.travelgo.service.ProviderCheckoutAttemptService;
import com.travelgo.service.StripeService;
import java.math.BigDecimal;
import java.time.*;
import java.util.HashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No provider request: the only SDK API call is replaced by a recording stub. */
class StripeProviderReadinessTests {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"), ZoneOffset.UTC);
    static final LocalDateTime DEADLINE = LocalDateTime.of(2030, 1, 2, 12, 0);
    final Booking booking = booking();
    final ProviderCheckoutAttemptService attempts = mock(ProviderCheckoutAttemptService.class);
    final RecordingStripe service = new RecordingStripe("sk_test_testingOnly", "whsec_testingOnly", true, false, "test", "http://localhost:8080", attempts);

    static Booking booking() {
        User user = new User(); user.setId(11L); user.setEmail("customer@travelgo.test");
        Booking booking = new Booking(); booking.setId(7L); booking.setUser(user); return booking;
    }
    static class RecordingStripe extends StripeService {
        SessionCreateParams parameters; RequestOptions options; int requests;
        String responseUrl = "https://checkout.stripe.com/c/pay/cs_test_Example";
        RecordingStripe(String key, String webhook, boolean enabled, boolean simulated, String mode, String origin, ProviderCheckoutAttemptService attempts) {
            super(key, webhook, enabled, simulated, mode, origin, CLOCK, attempts);
        }
        @Override protected Session createSession(SessionCreateParams parameters, RequestOptions options) {
            requests++; this.parameters = parameters; this.options = options;
            Session result = new Session(); result.setId("cs_test_Example"); result.setLivemode(false);
            result.setMode("payment"); result.setCurrency("usd"); result.setAmountTotal(parameters.getLineItems().get(0).getPriceData().getUnitAmount());
            result.setExpiresAt(parameters.getExpiresAt()); result.setMetadata(parameters.getMetadata());
            result.setClientReferenceId(parameters.getClientReferenceId()); result.setUrl(responseUrl); return result;
        }
    }
    ProviderCheckoutAttempt prepare() {
        ProviderCheckoutAttempt attempt = new ProviderCheckoutAttempt(); ReflectionTestUtils.setField(attempt, "id", 3L);
        attempt.setExpiresAt(CLOCK.instant().plusSeconds(23 * 3600).getEpochSecond());
        when(attempts.prepare(any(), any(), any(), any(), anyString(), anyString())).thenReturn(attempt); return attempt;
    }
    Session complete() throws Exception {
        prepare(); service.createCheckoutSession(booking, PaymentType.VISA_DOCUMENTATION, new BigDecimal("125.50"), DEADLINE,
                "http://localhost:8080/customer/payments/checkout/7?success=true", "http://localhost:8080/customer/payments/checkout/7?canceled=true");
        Session session = new Session(); session.setId("cs_test_Example"); session.setMode("payment"); session.setStatus("complete");
        session.setPaymentStatus("paid"); session.setCurrency("usd"); session.setLivemode(false); session.setAmountTotal(12550L);
        session.setPaymentIntent("pi_Example"); session.setMetadata(new HashMap<>(service.parameters.getMetadata()));
        session.setClientReferenceId(service.parameters.getClientReferenceId()); session.setCreated(CLOCK.instant().getEpochSecond());
        session.setExpiresAt(service.parameters.getExpiresAt()); return session;
    }
    @Test void disabledProviderNeverCallsNetworkAndSimulationCannotOverlapCheckout() {
        var disabled = new RecordingStripe("", "", false, true, "test", "http://localhost:8080", attempts);
        assertDoesNotThrow(disabled::validateConfiguration);
        assertThrows(IllegalStateException.class, () -> disabled.createCheckoutSession(booking, PaymentType.VISA_DOCUMENTATION, BigDecimal.ONE, DEADLINE, "", ""));
        assertEquals(0, disabled.requests);
        var overlap = new RecordingStripe("sk_test_testingOnly", "whsec_testingOnly", true, true, "test", "http://localhost:8080", attempts);
        assertThrows(IllegalStateException.class, overlap::validateConfiguration);
    }
    @Test void keyModeWebhookAndRemoteHttpsAreValidatedWithoutCredentialsInErrors() {
        for (var invalid : new RecordingStripe[] {
                new RecordingStripe("sk_live_DoNotExpose", "whsec_testingOnly", true, false, "test", "https://travelgo.test", attempts),
                new RecordingStripe("sk_test_testingOnly", "", true, false, "test", "https://travelgo.test", attempts),
                new RecordingStripe("sk_test_testingOnly", "whsec_testingOnly", true, false, "test", "http://travelgo.test", attempts),
                new RecordingStripe("sk_live_testingOnly", "whsec_testingOnly", true, false, "live", "http://localhost:8080", attempts) }) {
            var failure = assertThrows(IllegalStateException.class, invalid::validateConfiguration);
            assertFalse(failure.getMessage().contains("DoNotExpose")); assertEquals(0, invalid.requests);
        }
    }
    @Test void quoteUsesMinorUnitsMetadataCardOnlyAndPerRequestCredentials() throws Exception {
        Session completed = complete();
        assertEquals(12550, service.parameters.getLineItems().get(0).getPriceData().getUnitAmount());
        assertEquals("usd", service.parameters.getLineItems().get(0).getPriceData().getCurrency());
        assertEquals(java.util.List.of(SessionCreateParams.PaymentMethodType.CARD), service.parameters.getPaymentMethodTypes());
        assertEquals(service.parameters.getMetadata(), service.parameters.getPaymentIntentData().getMetadata());
        assertEquals("11", service.parameters.getMetadata().get("customerId"));
        assertEquals("3", service.parameters.getMetadata().get("checkoutAttemptId"));
        assertNotNull(service.options.getIdempotencyKey()); assertEquals("sk_test_testingOnly", service.options.getApiKey());
        assertTrue(service.parameters.getExpiresAt() <= DEADLINE.toEpochSecond(ZoneOffset.UTC));
        assertDoesNotThrow(() -> service.validateCompletedSession(completed, booking, PaymentType.VISA_DOCUMENTATION, new BigDecimal("125.50")));
        verify(attempts).verify(eq(3L), eq(booking), eq(PaymentType.VISA_DOCUMENTATION), eq(new BigDecimal("125.50")), anyLong(), eq("test"), anyString(), eq("cs_test_Example"), anyLong());
    }
    @Test void preparedRetriesKeepIdenticalParametersAndReuseSavedUrl() throws Exception {
        ProviderCheckoutAttempt attempt = prepare();
        String success = "http://localhost:8080/customer/payments/checkout/7?success=true";
        String cancel = "http://localhost:8080/customer/payments/checkout/7?canceled=true";
        service.createCheckoutSession(booking, PaymentType.FULL_PACKAGE, BigDecimal.TEN, DEADLINE, success, cancel);
        long expiry = service.parameters.getExpiresAt(); String key = service.options.getIdempotencyKey();
        String product = service.parameters.getLineItems().get(0).getPriceData().getProductData().getName();
        booking.getUser().setEmail("changed@travelgo.test");
        service.createCheckoutSession(booking, PaymentType.FULL_PACKAGE, BigDecimal.TEN, DEADLINE, success, cancel);
        assertEquals(expiry, service.parameters.getExpiresAt()); assertEquals(key, service.options.getIdempotencyKey());
        assertEquals(product, service.parameters.getLineItems().get(0).getPriceData().getProductData().getName());
        assertNull(service.parameters.getCustomerEmail());
        attempt.setCheckoutUrl("https://checkout.stripe.com/c/pay/cs_test_Example");
        assertEquals(attempt.getCheckoutUrl(), service.createCheckoutSession(booking, PaymentType.FULL_PACKAGE, BigDecimal.TEN, DEADLINE, success, cancel));
        assertEquals(2, service.requests);
    }
    @Test void excessivePrecisionAndLegacyPaymentTypesAreRejectedBeforeProvider() {
        assertThrows(IllegalArgumentException.class, () -> service.createCheckoutSession(booking, PaymentType.FULL_PACKAGE, new BigDecimal("1.001"), DEADLINE, "", ""));
        assertThrows(IllegalArgumentException.class, () -> service.createCheckoutSession(booking, PaymentType.PACKAGE_DEPOSIT, BigDecimal.ONE, DEADLINE, "", ""));
        assertEquals(0, service.requests);
    }
    @Test void mismatchedReturnAddressAndProviderRedirectAreRejected() throws Exception {
        prepare();
        assertThrows(IllegalArgumentException.class, () -> service.createCheckoutSession(booking, PaymentType.FULL_PACKAGE, BigDecimal.ONE, DEADLINE,
                "https://evil.test/customer/payments/checkout/7", "http://localhost:8080/customer/payments/checkout/7"));
        service.responseUrl = "https://checkout.stripe.com.evil.test/payment";
        assertThrows(IllegalStateException.class, () -> service.createCheckoutSession(booking, PaymentType.FULL_PACKAGE, BigDecimal.ONE, DEADLINE,
                "http://localhost:8080/customer/payments/checkout/7", "http://localhost:8080/customer/payments/checkout/7"));
    }
    @Test void wrongCurrencyAmountCustomerDeploymentAndModeCannotBecomePayment() throws Exception {
        for (int index = 0; index < 5; index++) {
            Session session = complete();
            switch (index) { case 0 -> session.setCurrency("eur"); case 1 -> session.setAmountTotal(1L);
                case 2 -> session.getMetadata().put("customerId", "99"); case 3 -> session.getMetadata().put("application", "other");
                case 4 -> session.setLivemode(true); }
            assertThrows(IllegalArgumentException.class, () -> service.validateCompletedSession(session, booking, PaymentType.VISA_DOCUMENTATION, new BigDecimal("125.50")));
        }
    }
    @Test void unpaidOrWrongQuoteExpiryCannotBecomePayment() throws Exception {
        Session unpaid = complete(); unpaid.setPaymentStatus("unpaid");
        assertThrows(IllegalArgumentException.class, () -> service.validateCompletedSession(unpaid, booking, PaymentType.VISA_DOCUMENTATION, new BigDecimal("125.50")));
        Session late = complete(); late.setExpiresAt(DEADLINE.plusMinutes(1).toEpochSecond(ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () -> service.validateCompletedSession(late, booking, PaymentType.VISA_DOCUMENTATION, new BigDecimal("125.50")));
    }
    @Test void webhookSignatureRejectsTamperingAndWrongMode() throws Exception {
        String payload = "{\"id\":\"evt_Test\",\"object\":\"event\",\"livemode\":false,\"type\":\"checkout.session.completed\",\"data\":{\"object\":{}}}";
        long time = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("whsec_testingOnly".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String hash = java.util.HexFormat.of().formatHex(mac.doFinal((time + "." + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Event event = service.verifyWebhook(payload, "t=" + time + ",v1=" + hash); assertDoesNotThrow(() -> service.validateEvent(event));
        assertThrows(SignatureVerificationException.class, () -> service.verifyWebhook(payload + " ", "t=" + time + ",v1=" + hash));
        event.setLivemode(true); assertThrows(IllegalArgumentException.class, () -> service.validateEvent(event));
    }
}
