package com.travelgo.controller;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.enums.PaymentStatus;
import com.travelgo.enums.PaymentType;
import com.travelgo.service.BookingService;
import com.travelgo.service.PaymentService;
import com.travelgo.service.StripeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/webhooks/stripe")
public class StripeWebhookController {

    private final StripeService stripeService;
    private final PaymentService paymentService;
    private final BookingService bookingService;
    private final UserDetailsService userDetailsService;

    public StripeWebhookController(StripeService stripeService, PaymentService paymentService, BookingService bookingService, UserDetailsService userDetailsService) {
        this.stripeService = stripeService;
        this.paymentService = paymentService;
        this.bookingService = bookingService;
        this.userDetailsService = userDetailsService;
    }

    @PostMapping
    public ResponseEntity<String> handleStripeWebhook(@RequestBody String payload, @RequestHeader("Stripe-Signature") String sigHeader) {
        Event event = null;

        try {
            event = Webhook.constructEvent(payload, sigHeader, stripeService.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            // Fallback for simulated local demo (we bypass signature if secret is a placeholder)
            if (stripeService.getWebhookSecret().startsWith("whsec_placeholder")) {
                System.out.println("Bypassing Stripe signature verification due to placeholder secret.");
                // We don't have a real event object if it wasn't parsed, so in simulated mode this endpoint might not be used, 
                // but we handle the real world scenario below.
                return ResponseEntity.badRequest().body("Signature verification failed.");
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Signature verification failed");
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Payload parsing failed");
        }

        if ("checkout.session.completed".equals(event.getType())) {
            Session session = (Session) event.getDataObjectDeserializer().getObject().orElse(null);
            if (session != null) {
                try {
                    Long bookingId = Long.valueOf(session.getMetadata().get("bookingId"));
                    PaymentType type = PaymentType.valueOf(session.getMetadata().get("paymentType"));
                    BigDecimal amount = new BigDecimal(session.getAmountTotal()).divide(new BigDecimal("100"));
                    String reference = session.getPaymentIntent();
                    if (reference == null || reference.isEmpty()) {
                        reference = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                    }

                    Booking booking = bookingService.findById(bookingId).orElseThrow(() -> new IllegalArgumentException("Booking not found"));

                    // Setup Auth Context to satisfy ownership rules in WorkflowRules
                    UserDetails userDetails = userDetailsService.loadUserByUsername(booking.getUser().getEmail());
                    Authentication auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    Payment payment = new Payment();
                    payment.setBooking(booking);
                    payment.setPaymentType(type);
                    payment.setAmount(amount);
                    payment.setPaymentMethod("Stripe Checkout");
                    payment.setTransactionReference(reference);

                    paymentService.processPayment(payment);

                } catch (Exception e) {
                    System.err.println("Failed to process webhook payment: " + e.getMessage());
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        }

        return ResponseEntity.ok("Success");
    }
}
