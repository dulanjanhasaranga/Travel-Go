package com.travelgo;

import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.checkout.Session;
import com.travelgo.controller.StripeWebhookController;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StripeWebhookSafetyTests {
    final StripeService stripe = mock(StripeService.class);
    final PaymentService payments = mock(PaymentService.class);
    final BookingService bookings = mock(BookingService.class);
    final UserDetailsService users = mock(UserDetailsService.class);
    final ProviderPaymentReviewService reviews = mock(ProviderPaymentReviewService.class);
    final StripeWebhookController controller = new StripeWebhookController(stripe, payments, bookings, users, reviews);
    final Booking booking = StripeProviderReadinessTests.booking();
    Event event; Session session;
    @BeforeEach void setup() throws Exception {
        session = new Session(); session.setId("cs_test_Test"); session.setPaymentIntent("pi_Test");
        session.setMetadata(Map.of("bookingId", "7", "paymentType", "VISA_DOCUMENTATION", "quoteDeadline", "1893585600", "checkoutAttemptId", "3"));
        event = mock(Event.class); when(event.getId()).thenReturn("evt_Test"); when(event.getType()).thenReturn("checkout.session.completed");
        when(event.getCreated()).thenReturn(1893499200L);
        var deserializer = mock(EventDataObjectDeserializer.class); when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(session));
        when(stripe.verifyWebhook("payload", "signature")).thenReturn(event);
        when(bookings.findById(7L)).thenReturn(Optional.of(booking));
        when(payments.expectedAmount(booking, PaymentType.VISA_DOCUMENTATION)).thenReturn(new BigDecimal("125.50"));
        when(payments.findByBookingId(7L)).thenReturn(List.of());
        when(users.loadUserByUsername(booking.getUser().getEmail())).thenReturn(org.springframework.security.core.userdetails.User
                .withUsername(booking.getUser().getEmail()).password("unused").roles("CUSTOMER").build());
        when(payments.processProviderPayment(any(),eq(3L),eq("cs_test_Test"))).thenAnswer(call -> call.getArgument(0));
    }
    @Test void validSignedPaymentUsesServerQuoteAndRestoresAuthenticationContext() {
        var previous = SecurityContextHolder.createEmptyContext(); SecurityContextHolder.setContext(previous);
        assertEquals(200, controller.handleStripeWebhook("payload", "signature").getStatusCode().value());
        assertSame(previous, SecurityContextHolder.getContext());
        var captured = org.mockito.ArgumentCaptor.forClass(Payment.class); verify(payments).processProviderPayment(captured.capture(),eq(3L),eq("cs_test_Test"));
        assertEquals(new BigDecimal("125.50"), captured.getValue().getAmount());
        assertEquals("pi_Test", captured.getValue().getTransactionReference()); verifyNoInteractions(reviews);
    }
    @Test void tamperedSessionProducesDurableReviewWithoutPayment() {
        doThrow(new IllegalArgumentException()).when(stripe).validateCompletedSession(any(), any(), any(), any());
        assertEquals(400, controller.handleStripeWebhook("payload", "signature").getStatusCode().value());
        verify(payments, never()).processProviderPayment(any(),anyLong(),anyString()); verify(reviews).record(eq(event), eq(session), eq(booking), anyString());
    }
    @Test void emptyDeserializerIsNotSilentlyAcknowledged() {
        when(event.getDataObjectDeserializer().getObject()).thenReturn(Optional.empty());
        assertEquals(400, controller.handleStripeWebhook("payload", "signature").getStatusCode().value());
        verifyNoInteractions(bookings); verify(reviews).record(eq(event), isNull(), isNull(), contains("API version"));
    }
    @Test void paidDuplicateAcknowledgesSameReferenceWithoutAnotherMutation() {
        Payment prior = new Payment(); prior.setPaymentType(PaymentType.VISA_DOCUMENTATION); prior.setPaymentStatus(PaymentStatus.PAID);
        prior.setAmount(new BigDecimal("125.50")); prior.setPaymentMethod("Stripe Checkout"); prior.setTransactionReference("pi_Test");
        when(payments.findByBookingId(7L)).thenReturn(List.of(prior));
        assertEquals("Already recorded", controller.handleStripeWebhook("payload", "signature").getBody());
        verify(payments).processProviderPayment(any(),eq(3L),eq("cs_test_Test")); verify(payments,never()).processPayment(any()); verifyNoInteractions(reviews);
    }
    @Test void differentExistingReferenceCreatesReviewRatherThanDiscardingAnExtraCharge() {
        Payment prior = new Payment(); prior.setPaymentType(PaymentType.VISA_DOCUMENTATION); prior.setPaymentStatus(PaymentStatus.PAID);
        prior.setAmount(new BigDecimal("125.50")); prior.setPaymentMethod("Stripe Checkout"); prior.setTransactionReference("pi_Other");
        when(payments.findByBookingId(7L)).thenReturn(List.of(prior));
        assertEquals(409, controller.handleStripeWebhook("payload", "signature").getStatusCode().value());
        verify(payments, never()).processProviderPayment(any(),anyLong(),anyString()); verify(reviews).record(eq(event), eq(session), eq(booking), contains("different"));
    }
    @Test void latePaymentAndSuspendedAccountRequireReconciliation() {
        when(event.getCreated()).thenReturn(1893585601L);
        assertEquals(409, controller.handleStripeWebhook("payload", "signature").getStatusCode().value());
        verify(payments, never()).processProviderPayment(any(),anyLong(),anyString());
        when(event.getCreated()).thenReturn(1893499200L);
        when(users.loadUserByUsername(anyString())).thenReturn(org.springframework.security.core.userdetails.User
                .withUsername("suspended@travelgo.test").password("unused").roles("CUSTOMER").disabled(true).build());
        assertEquals(409, controller.handleStripeWebhook("payload", "signature").getStatusCode().value());
        verify(payments, never()).processProviderPayment(any(),anyLong(),anyString());
    }
    @Test void concurrentDifferentPaymentCreatesReviewAndPersistenceFailureCanRetry() {
        Payment other = new Payment(); other.setTransactionReference("pi_Other"); other.setPaymentMethod("Stripe Checkout");
        when(payments.processProviderPayment(any(),anyLong(),anyString())).thenReturn(other);
        assertEquals(409, controller.handleStripeWebhook("payload", "signature").getStatusCode().value());
        when(payments.processProviderPayment(any(),anyLong(),anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private-database-diagnostic"));
        var response = controller.handleStripeWebhook("payload", "signature");
        assertEquals(500, response.getStatusCode().value()); assertFalse(response.getBody().contains("private-database"));
        verify(reviews, times(2)).record(eq(event), eq(session), eq(booking), anyString());
    }
    @Test void invalidSignatureDoesNotCreateReviewOrTrustUnverifiedMetadata() throws Exception {
        when(stripe.verifyWebhook(anyString(), anyString())).thenThrow(new com.stripe.exception.SignatureVerificationException("invalid", "signature"));
        assertEquals(400, controller.handleStripeWebhook("payload", "signature").getStatusCode().value());
        verifyNoInteractions(bookings, payments, reviews);
    }
}
