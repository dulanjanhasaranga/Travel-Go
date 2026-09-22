package com.travelgo.service;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import com.travelgo.entity.Booking;
import com.travelgo.enums.PaymentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class StripeService {

    private final String secretKey;
    private final String webhookSecret;

    public StripeService(@Value("${travelgo.stripe.secret-key}") String secretKey,
                         @Value("${travelgo.stripe.webhook-secret}") String webhookSecret) {
        this.secretKey = secretKey;
        this.webhookSecret = webhookSecret;
    }

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public String createCheckoutSession(Booking booking, PaymentType type, BigDecimal amount, String successUrl, String cancelUrl) throws StripeException {
        // Fallback for demo without valid keys
        if (secretKey.startsWith("sk_test_placeholder")) {
            return successUrl + "&simulated=true&session_id=fake_" + UUID.randomUUID().toString();
        }

        long amountInCents = amount.multiply(new BigDecimal("100")).longValue();

        String productName = "TravelGO Payment - " + type.name().replace("_", " ");
        if (booking.getTourPackage() != null) {
            productName += " (" + booking.getTourPackage().getName() + ")";
        }

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl + "&session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(cancelUrl)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setQuantity(1L)
                                .setPriceData(
                                        SessionCreateParams.LineItem.PriceData.builder()
                                                .setCurrency("usd")
                                                .setUnitAmount(amountInCents)
                                                .setProductData(
                                                        SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                                .setName(productName)
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .putMetadata("bookingId", booking.getId().toString())
                .putMetadata("paymentType", type.name())
                .setCustomerEmail(booking.getUser().getEmail())
                .build();

        Session session = Session.create(params);
        return session.getUrl();
    }

    public Session verifySession(String sessionId) throws StripeException {
        if (sessionId == null || sessionId.startsWith("fake_")) {
            return null; // Simulated or invalid
        }
        return Session.retrieve(sessionId);
    }
}
