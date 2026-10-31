package com.travelgo.config;

import static com.travelgo.config.AuthenticationRequestLimiter.RequestKind.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Deterministic monotonic-time tests; no application context, provider or database. */
class AuthenticationRequestLimiterTests {
    private final AtomicLong millis = new AtomicLong();
    private final AuthenticationRequestLimiter limiter = new AuthenticationRequestLimiter(
            AuthenticationAbuseProperties.defaults(), millis::get);

    @Test void repeatedAccountFromOneSourceIsLimitedWithoutPermanentLockout() {
        for (int i = 0; i < 10; i++) assertTrue(limiter.acquire(LOGIN, "192.0.2.1", "traveller@example.test").allowed());
        var denied = limiter.acquire(LOGIN, "192.0.2.1", "traveller@example.test");
        assertFalse(denied.allowed());
        assertEquals(900, denied.retryAfterSeconds());
        millis.set(899_001);
        assertEquals(1, limiter.acquire(LOGIN, "192.0.2.1", "traveller@example.test").retryAfterSeconds());
        millis.set(900_000);
        assertTrue(limiter.acquire(LOGIN, "192.0.2.1", "traveller@example.test").allowed());
    }

    @Test void emailCaseAndSurroundingWhitespaceDoNotResetTheBudget() {
        for (int i = 0; i < 10; i++) assertTrue(limiter.acquire(LOGIN, "192.0.2.2", "  Traveller@Example.Test ").allowed());
        assertFalse(limiter.acquire(LOGIN, "192.0.2.2", "traveller@example.test").allowed());
    }

    @Test void attackingOneAccountDoesNotLockItFromAnotherSource() {
        for (int i = 0; i < 10; i++) limiter.acquire(LOGIN, "192.0.2.3", "traveller@example.test");
        assertFalse(limiter.acquire(LOGIN, "192.0.2.3", "traveller@example.test").allowed());
        assertTrue(limiter.acquire(LOGIN, "192.0.2.4", "traveller@example.test").allowed());
    }

    @Test void passwordSprayingDifferentAccountsStillConsumesTheSourceBudget() {
        for (int i = 0; i < 60; i++) assertTrue(limiter.acquire(LOGIN, "192.0.2.5", "synthetic" + i + "@example.test").allowed());
        assertFalse(limiter.acquire(LOGIN, "192.0.2.5", "another@example.test").allowed());
    }

    @Test void deniedPairRequestsDoNotConsumeOtherAvailableBudgets() {
        for (int i = 0; i < 10; i++) limiter.acquire(LOGIN, "192.0.2.6", "first@example.test");
        for (int i = 0; i < 100; i++) assertFalse(limiter.acquire(LOGIN, "192.0.2.6", "first@example.test").allowed());
        for (int i = 0; i < 50; i++) assertTrue(limiter.acquire(LOGIN, "192.0.2.6", "other" + i + "@example.test").allowed());
        assertFalse(limiter.acquire(LOGIN, "192.0.2.6", "last@example.test").allowed());
    }

    @Test void registrationAndMailHaveSeparateStricterBudgets() {
        for (int i = 0; i < 3; i++) assertTrue(limiter.acquire(REGISTRATION, "192.0.2.7", "new@example.test").allowed());
        assertFalse(limiter.acquire(REGISTRATION, "192.0.2.7", "new@example.test").allowed());
        assertTrue(limiter.acquire(LOGIN, "192.0.2.7", "new@example.test").allowed());
        for (int i = 0; i < 3; i++) assertTrue(limiter.acquire(EMAIL_REQUEST, "192.0.2.7", "new@example.test").allowed());
        assertFalse(limiter.acquire(EMAIL_REQUEST, "192.0.2.7", "new@example.test").allowed());
    }

    @Test void sourceMailBudgetPersistsAfterShorterAccountWindowExpires() {
        for (int i = 0; i < 20; i++) assertTrue(limiter.acquire(EMAIL_REQUEST, "192.0.2.8", "synthetic" + i + "@example.test").allowed());
        millis.set(900_000);
        var denied = limiter.acquire(EMAIL_REQUEST, "192.0.2.8", "fresh@example.test");
        assertFalse(denied.allowed());
        assertEquals(2700, denied.retryAfterSeconds());
        millis.set(3_600_000);
        assertTrue(limiter.acquire(EMAIL_REQUEST, "192.0.2.8", "fresh@example.test").allowed());
    }

    @Test void tokenSubmissionsHaveOneSourceBudgetWithoutRetainingTokenValues() {
        for (int i = 0; i < 30; i++) assertTrue(limiter.acquire(TOKEN_SUBMISSION, "192.0.2.9", null).allowed());
        assertFalse(limiter.acquire(TOKEN_SUBMISSION, "192.0.2.9", "ignored@example.test").allowed());
        assertEquals(1, limiter.trackedBuckets());
    }

    @Test void capacitySaturationDeniesNewIdentitiesWithoutEvictingExistingLimits() {
        var bounded = new AuthenticationRequestLimiter(properties(true, 2), millis::get);
        assertTrue(bounded.acquire(LOGIN, "192.0.2.10", "first@example.test").allowed());
        assertFalse(bounded.acquire(LOGIN, "192.0.2.11", "second@example.test").allowed());
        assertEquals(2, bounded.trackedBuckets());
        for (int i = 1; i < 10; i++) assertTrue(bounded.acquire(LOGIN, "192.0.2.10", "first@example.test").allowed());
        assertFalse(bounded.acquire(LOGIN, "192.0.2.10", "first@example.test").allowed());
        millis.set(900_000);
        assertTrue(bounded.acquire(LOGIN, "192.0.2.11", "second@example.test").allowed());
        assertEquals(2, bounded.trackedBuckets());
    }

    @Test void concurrentAttemptsCannotExceedTheBudget() throws Exception {
        var workers = Executors.newFixedThreadPool(12);
        var ready = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) attempts.add(workers.submit(() -> {
                ready.await();
                return limiter.acquire(LOGIN, "192.0.2.12", "same@example.test").allowed();
            }));
            ready.countDown();
            int admitted = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) admitted++;
            assertEquals(10, admitted);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test void disabledProtectionKeepsNoIdentityState() {
        var disabled = new AuthenticationRequestLimiter(properties(false, 2), millis::get);
        for (int i = 0; i < 100; i++) assertTrue(disabled.acquire(LOGIN, "192.0.2.13", "same@example.test").allowed());
        assertEquals(0, disabled.trackedBuckets());
    }

    @Test void malformedIdentitiesHaveBoundedSharedBudgets() {
        for (int i = 0; i < 10; i++) assertTrue(limiter.acquire(LOGIN, null, "x".repeat(100_000)).allowed());
        assertFalse(limiter.acquire(LOGIN, "", "y".repeat(100_000)).allowed());
        assertEquals(2, limiter.trackedBuckets());
    }

    @Test void unsafeConfigurationFailsAtBindingInsteadOfDisablingProtection() {
        assertThrows(IllegalArgumentException.class, () -> properties(true, 1));
        assertThrows(IllegalArgumentException.class, () -> new AuthenticationAbuseProperties(true, 2,
                0, 10, 900, 10, 3, 3600, 20, 3600, 3, 900, 30, 900));
        assertThrows(IllegalArgumentException.class, () -> new AuthenticationAbuseProperties(true, 2,
                60, 10, 86_401, 10, 3, 3600, 20, 3600, 3, 900, 30, 900));
    }

    private static AuthenticationAbuseProperties properties(boolean enabled, int maximumBuckets) {
        return new AuthenticationAbuseProperties(enabled, maximumBuckets, 60, 10, 900,
                10, 3, 3600, 20, 3600, 3, 900, 30, 900);
    }
}
