package com.travelgo;

import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.travelgo.entity.Booking;
import com.travelgo.enums.PaymentType;
import com.travelgo.repository.ProviderCheckoutAttemptRepository;
import com.travelgo.repository.ProviderPaymentReviewRepository;
import com.travelgo.service.ProviderCheckoutAttemptService;
import com.travelgo.service.ProviderPaymentReviewService;
import java.math.BigDecimal;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

class ProviderCheckoutPersistenceTests extends WorkflowIntegrationTests {
    @Autowired ProviderCheckoutAttemptService attempts;
    @Autowired ProviderCheckoutAttemptRepository attemptRepository;
    @Autowired ProviderPaymentReviewService reviews;
    @Autowired ProviderPaymentReviewRepository reviewRepository;
    @Test void checkoutRetriesReuseCommittedExpiryUrlAndAttempt() throws Exception {
        Booking booking = create(false); var visa = quote(booking); login(customer);
        var first = attempts.prepare(booking, PaymentType.VISA_DOCUMENTATION, visa.getTotalCharge(), visa.getPaymentExpiresAt(), "test", "test-deployment");
        clock.instant = clock.instant.plusSeconds(120);
        var retry = attempts.prepare(booking, PaymentType.VISA_DOCUMENTATION, visa.getTotalCharge(), visa.getPaymentExpiresAt(), "test", "test-deployment");
        assertEquals(first.getId(), retry.getId()); assertEquals(first.getExpiresAt(), retry.getExpiresAt());
        attempts.completed(first.getId(), "cs_test_Example", "https://checkout.stripe.com/c/pay/cs_test_Example");
        assertEquals("cs_test_Example", attempts.prepare(booking, PaymentType.VISA_DOCUMENTATION, visa.getTotalCharge(), visa.getPaymentExpiresAt(), "test", "test-deployment").getSessionReference());
        assertEquals(1, attemptRepository.findByBooking_IdAndPaymentTypeOrderByIdDesc(booking.getId(), PaymentType.VISA_DOCUMENTATION).size());
    }
    @Test void ownershipTamperedAmountStaleDeadlineAndBoundaryPreventReservation() throws Exception {
        Booking booking = create(false); var visa = quote(booking); login(other);
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> attempts.prepare(booking, PaymentType.VISA_DOCUMENTATION, visa.getTotalCharge(), visa.getPaymentExpiresAt(), "test", "test"));
        login(customer);
        assertThrows(IllegalStateException.class, () -> attempts.prepare(booking, PaymentType.VISA_DOCUMENTATION, BigDecimal.ONE, visa.getPaymentExpiresAt(), "test", "test"));
        assertThrows(IllegalStateException.class, () -> attempts.prepare(booking, PaymentType.VISA_DOCUMENTATION, visa.getTotalCharge(), visa.getPaymentExpiresAt().plusMinutes(1), "test", "test"));
        clock.instant = visa.getPaymentExpiresAt().toInstant(ZoneOffset.UTC).minusSeconds(1859);
        assertThrows(IllegalStateException.class, () -> attempts.prepare(booking, PaymentType.VISA_DOCUMENTATION, visa.getTotalCharge(), visa.getPaymentExpiresAt(), "test", "test"));
        assertTrue(attemptRepository.findByBooking_IdAndPaymentTypeOrderByIdDesc(booking.getId(), PaymentType.VISA_DOCUMENTATION).isEmpty());
    }
    @Test void simultaneousCheckoutRequestsReserveOneAttemptUnderBookingLock() throws Exception {
        Booking booking = create(false); var visa = quote(booking); CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Long> work = () -> { login(customer); ready.countDown(); try { start.await(); return attempts.prepare(booking, PaymentType.VISA_DOCUMENTATION, visa.getTotalCharge(), visa.getPaymentExpiresAt(), "test", "test").getId(); } finally { SecurityContextHolder.clearContext(); } };
        try { Future<Long> a = executor.submit(work), b = executor.submit(work); assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown(); assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)); }
        finally { executor.shutdownNow(); }
        assertEquals(1, attemptRepository.findByBooking_IdAndPaymentTypeOrderByIdDesc(booking.getId(), PaymentType.VISA_DOCUMENTATION).size());
    }
    @Test void verifiedSessionEvidenceMustMatchStoredAttempt() throws Exception {
        Booking booking = create(false); var visa = quote(booking); login(customer);
        var attempt = attempts.prepare(booking, PaymentType.VISA_DOCUMENTATION, visa.getTotalCharge(), visa.getPaymentExpiresAt(), "test", "test");
        attempts.completed(attempt.getId(), "cs_test_Example", "https://checkout.stripe.com/c/pay/cs_test_Example");
        long deadline = visa.getPaymentExpiresAt().toEpochSecond(ZoneOffset.UTC);
        assertDoesNotThrow(() -> attempts.verify(attempt.getId(), booking, PaymentType.VISA_DOCUMENTATION, visa.getTotalCharge(), deadline, "test", "test", "cs_test_Example", attempt.getExpiresAt()));
        assertThrows(IllegalArgumentException.class, () -> attempts.verify(attempt.getId(), booking, PaymentType.VISA_DOCUMENTATION, visa.getTotalCharge(), deadline, "test", "test", "cs_test_Different", attempt.getExpiresAt()));
        assertThrows(IllegalArgumentException.class, () -> attempts.verify(attempt.getId(), booking, PaymentType.VISA_DOCUMENTATION, BigDecimal.ONE, deadline, "test", "test", "cs_test_Example", attempt.getExpiresAt()));
    }
    @Test void reviewIsDurableIdempotentAndAcknowledgementCannotModifyFinancialRecords() {
        Booking booking = create(false); Event event = new Event(); event.setId("evt_" + booking.getId()); event.setLivemode(false);
        Session session = new Session(); session.setId("cs_test_Example"); session.setPaymentIntent("pi_Example"); session.setAmountTotal(4000L); session.setCurrency("usd");
        reviews.record(event, session, booking, "Deadline requires staff review."); reviews.record(event, session, booking, "Repeated event.");
        assertEquals(1, reviewRepository.findAll().stream().filter(r -> event.getId().equals(r.getEventReference())).count());
        long paymentCount = paymentRepo.count(), refundCount = refundRepo.count();
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> reviews.findOpen()); login(admin);
        var record = reviews.findOpen().stream().filter(r -> event.getId().equals(r.getEventReference())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("40.00"), record.getAmount()); assertThrows(IllegalArgumentException.class, () -> reviews.acknowledge(record.getId(), ""));
        var resolved = reviews.acknowledge(record.getId(), "Provider event investigated; settlement remains a separate authorized operation.");
        assertEquals("RESOLVED", resolved.getStatus()); assertEquals(admin.getId(), resolved.getReviewedBy().getId()); assertEquals(clock.instant, resolved.getReviewedAt());
        assertEquals(paymentCount, paymentRepo.count()); assertEquals(refundCount, refundRepo.count());
    }
}
