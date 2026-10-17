package com.travelgo.controller;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.enums.PaymentStatus;
import com.travelgo.enums.PaymentType;
import com.travelgo.service.BookingService;
import com.travelgo.service.PaymentService;
import com.travelgo.service.ProviderPaymentReviewService;
import com.travelgo.service.StripeService;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "travelgo.stripe.enabled", havingValue = "true")
@RequestMapping("/api/webhooks/stripe")
public class StripeWebhookController {
    private final StripeService stripeService;
    private final PaymentService paymentService;
    private final BookingService bookingService;
    private final UserDetailsService userDetailsService;
    private final ProviderPaymentReviewService reviews;

    public StripeWebhookController(StripeService stripeService, PaymentService paymentService,
                                   BookingService bookingService, UserDetailsService userDetailsService,
                                   ProviderPaymentReviewService reviews) {
        this.stripeService = stripeService;
        this.paymentService = paymentService;
        this.bookingService = bookingService;
        this.userDetailsService = userDetailsService;
        this.reviews = reviews;
    }

    @PostMapping
    public ResponseEntity<String> handleStripeWebhook(@RequestBody String payload,
                                                       @RequestHeader("Stripe-Signature") String signature) {
        Event event;
        try {
            event = stripeService.verifyWebhook(payload, signature);
        } catch (SignatureVerificationException | IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body("Invalid Stripe signature or payload.");
        } catch (IllegalStateException unavailable) {
            return ResponseEntity.status(503).body("Stripe checkout is unavailable.");
        } catch (Exception invalidPayload) {
            return ResponseEntity.badRequest().body("Invalid Stripe payload.");
        }
        try { stripeService.validateEvent(event); }
        catch (IllegalArgumentException invalid) {
            recordReview(event, null, null, "Unexpected provider event, account or mode.");
            return ResponseEntity.badRequest().body("Unexpected Stripe event, account or payment mode.");
        }
        if (!"checkout.session.completed".equals(event.getType())) return ResponseEntity.ok("Ignored event type");

        SecurityContext previous = SecurityContextHolder.getContext();
        Session session = null;
        Booking booking = null;
        try {
            // An API-version mismatch must remain visible for reconciliation, never acknowledged as processed.
            Object data = event.getDataObjectDeserializer().getObject().orElse(null);
            if (!(data instanceof Session)) {
                recordReview(event, null, null, "Unsupported session payload; check webhook API version.");
                return ResponseEntity.badRequest().body("Unsupported Stripe session payload. Check the webhook API version.");
            }
            session = (Session) data;
            if (session.getMetadata() == null) throw new IllegalArgumentException("Booking metadata is required.");
            Long bookingId = Long.valueOf(session.getMetadata().get("bookingId"));
            PaymentType type = PaymentType.valueOf(session.getMetadata().get("paymentType"));
            booking = bookingService.findById(bookingId).orElseThrow(() -> new IllegalArgumentException("Unknown booking."));
            BigDecimal expected = paymentService.expectedAmount(booking, type);
            stripeService.validateCompletedSession(session, booking, type, expected);
            if (event.getCreated() == null || event.getCreated() > Long.parseLong(session.getMetadata().get("quoteDeadline"))) {
                recordReview(event, session, booking, "Payment arrived after its quote deadline.");
                return ResponseEntity.status(409).body("Payment arrived after its quote deadline. Staff reconciliation is required.");
            }
            String reference = session.getPaymentIntent();
            boolean alreadyRecorded = false;
            for (Payment prior : paymentService.findByBookingId(bookingId)) {
                if (prior.getPaymentType() != type || (prior.getPaymentStatus() != PaymentStatus.PAID && prior.getPaymentStatus() != PaymentStatus.REFUNDED)) continue;
                if ("Stripe Checkout".equals(prior.getPaymentMethod()) && reference.equals(prior.getTransactionReference())
                        && expected.compareTo(prior.getAmount()) == 0) { alreadyRecorded = true; continue; }
                recordReview(event, session, booking, "A different successful payment is already recorded.");
                return ResponseEntity.status(409).body("A different payment is already recorded. Staff reconciliation is required.");
            }
            var customer = userDetailsService.loadUserByUsername(booking.getUser().getEmail());
            if (!customer.isEnabled() || customer.getAuthorities().stream().noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
                recordReview(event, session, booking, "Customer account is inactive or no longer a customer.");
                return ResponseEntity.status(409).body("The customer account requires staff reconciliation.");
            }
            SecurityContext providerContext = SecurityContextHolder.createEmptyContext();
            providerContext.setAuthentication(new UsernamePasswordAuthenticationToken(customer, null, customer.getAuthorities()));
            SecurityContextHolder.setContext(providerContext);
            Payment payment = new Payment();
            payment.setBooking(booking); payment.setPaymentType(type); payment.setAmount(expected);
            payment.setPaymentMethod("Stripe Checkout"); payment.setTransactionReference(reference);
            Long attemptId = Long.valueOf(session.getMetadata().get("checkoutAttemptId"));
            Payment recorded = paymentService.processProviderPayment(payment, attemptId, session.getId());
            if (!reference.equals(recorded.getTransactionReference()) || !"Stripe Checkout".equals(recorded.getPaymentMethod())) {
                recordReview(event, session, booking, "A different successful payment was recorded concurrently.");
                return ResponseEntity.status(409).body("A different payment was recorded concurrently. Staff reconciliation is required.");
            }
            return ResponseEntity.ok(alreadyRecorded ? "Already recorded" : "Recorded");
        } catch (IllegalArgumentException invalid) {
            recordReview(event, session, booking, "Session does not match the current booking, amount, currency or quote.");
            return ResponseEntity.badRequest().body("Stripe checkout does not match a valid booking and quote.");
        } catch (IllegalStateException review) {
            recordReview(event, session, booking, "Business workflow rejected this payment; inspect stage and deadline.");
            return ResponseEntity.status(409).body("This payment requires staff reconciliation. No new payment was recorded.");
        } catch (Exception failure) {
            recordReview(event, session, booking, "Payment persistence failed; reconcile or retry the signed event.");
            return ResponseEntity.internalServerError().body("Payment processing failed. Retry or reconcile this event.");
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private void recordReview(Event event, Session session, Booking booking, String reason) {
        try { reviews.record(event, session, booking, reason); }
        catch (Exception unavailable) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Stripe event {} could not be added to payment review; inspect provider retries", event == null ? "unknown" : event.getId());
        }
    }
}
