package com.travelgo.service;

import com.travelgo.config.EmailFeedbackConfiguration;
import com.travelgo.entity.OutboundEmail;
import com.travelgo.entity.User;
import com.travelgo.repository.OutboundEmailRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real payload/feedback parsers, fake HTTPS exchange and mocked transaction boundaries. */
class BrevoOutboxDeliveryTests {
    static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
    static final String TOKEN = "synthetic_webhook_secret_12345678901234567890";

    @Test void claimCommitsBeforeApiCallAndActualReceiptPreventsDuplicateDispatch() {
        var fixture = new Fixture();
        fixture.sender = fixture.sender(request -> {
            assertEquals("SENDING", fixture.email.getStatus()); assertEquals(1, fixture.email.getAttempts());
            assertEquals(1, fixture.commits.get()); // The SENDING claim has committed before any external side effect.
            fixture.calls.incrementAndGet(); return fixture.receipt("<provider-1@relay.test>");
        });
        var delivery = fixture.delivery(true); delivery.deliver(1L); delivery.deliver(1L);
        assertEquals(1, fixture.calls.get()); assertEquals("SENT", fixture.email.getStatus());
        assertEquals("provider-1@relay.test", fixture.email.getProviderMessageId());
        assertEquals(NOW, fixture.email.getSentAt()); assertEquals(1, fixture.email.getAttempts());
    }

    @Test void everyHttpFailureRequiresReviewAndNeverAutomaticallyResends() {
        for (int status : List.of(302, 400, 401, 429, 500, 503)) {
            var fixture = new Fixture();
            fixture.sender = fixture.sender(request -> {
                fixture.calls.incrementAndGet();
                return CompletableFuture.completedFuture(new BrevoTransactionalMailSender.Response(status, "sensitive-provider-body".getBytes(StandardCharsets.UTF_8)));
            });
            var delivery = fixture.delivery(true); delivery.deliver(1L); delivery.deliver(1L);
            assertEquals("REVIEW_REQUIRED", fixture.email.getStatus()); assertEquals(1, fixture.calls.get());
            assertNull(fixture.email.getProviderMessageId()); assertNull(fixture.email.getSentAt());
            assertEquals(1, fixture.email.getAttempts());
        }
    }

    @Test void verifiedCallbackBeforeApiResponsePreservesDeliveryAndNormalizesEnvelopeDifference() {
        var fixture = new Fixture();
        fixture.sender = fixture.sender(request -> {
            fixture.feedback("provider-1@relay.test", "delivered", NOW);
            return fixture.receipt("<provider-1@relay.test>");
        });
        fixture.delivery(true).deliver(1L);
        assertEquals("SENT", fixture.email.getStatus()); assertEquals("DELIVERED", fixture.email.getRecipientDeliveryStatus());
        assertEquals("provider-1@relay.test", fixture.email.getProviderMessageId());
        assertEquals(NOW, fixture.email.getRecipientFeedbackReceivedAt());
    }

    @Test void apiReceiptBeforeCallbackAcceptsEquivalentEnvelope() {
        var fixture = new Fixture(); fixture.sender = fixture.sender(request -> fixture.receipt("provider-1@relay.test"));
        fixture.delivery(true).deliver(1L);
        fixture.feedback("<provider-1@relay.test>", "delivered", NOW);
        assertEquals("SENT", fixture.email.getStatus()); assertEquals("DELIVERED", fixture.email.getRecipientDeliveryStatus());
        assertEquals("provider-1@relay.test", fixture.email.getProviderMessageId());
    }

    @Test void apiReceiptFirstConflictingCallbackRequiresReviewWithoutReplacingReceiptOrOutcome() {
        var fixture = new Fixture();
        fixture.sender = fixture.sender(request -> {
            fixture.calls.incrementAndGet(); return fixture.receipt("<api-id@relay.test>");
        });
        var delivery = fixture.delivery(true); delivery.deliver(1L);
        fixture.feedback("<conflicting-callback-id@relay.test>", "delivered", NOW);
        assertEquals("REVIEW_REQUIRED_CONFLICT", fixture.email.getStatus());
        assertEquals("api-id@relay.test", fixture.email.getProviderMessageId());
        assertNull(fixture.email.getRecipientDeliveryStatus()); assertNull(fixture.email.getRecipientDeliveryUpdatedAt());
        assertEquals(NOW, fixture.email.getRecipientFeedbackReceivedAt()); assertEquals(NOW, fixture.email.getSentAt());
        // Even without a correlatable recipient outcome, authenticated provider evidence forbids a retry.
        assertThrows(IllegalStateException.class, () -> fixture.review().review(1L, "REQUEUE_NOT_ACCEPTED", "case-123",
                "Investigated conflicting provider evidence.", true));
        fixture.feedback("api-id@relay.test", "delivered", NOW.plusSeconds(1));
        fixture.feedback("conflicting-callback-id@relay.test", "spam", NOW.plusSeconds(2));
        delivery.deliver(1L);
        assertEquals("REVIEW_REQUIRED_CONFLICT", fixture.email.getStatus()); assertEquals(1, fixture.calls.get());
        assertEquals("api-id@relay.test", fixture.email.getProviderMessageId());
        assertEquals("DELIVERED", fixture.email.getRecipientDeliveryStatus());
        assertEquals(NOW.plusSeconds(1), fixture.email.getRecipientDeliveryUpdatedAt());
    }

    @Test void mismatchAfterMatchedRecipientOutcomePreservesFirstOutcomeAndRequiresReview() {
        var fixture = new Fixture(); fixture.sender = fixture.sender(request -> fixture.receipt("api-id@relay.test"));
        fixture.delivery(true).deliver(1L);
        fixture.feedback("api-id@relay.test", "delivered", NOW);
        fixture.feedback("another-id@relay.test", "hard_bounce", NOW.plusSeconds(1));
        assertEquals("REVIEW_REQUIRED_CONFLICT", fixture.email.getStatus());
        assertEquals("api-id@relay.test", fixture.email.getProviderMessageId());
        assertEquals("DELIVERED", fixture.email.getRecipientDeliveryStatus());
        assertEquals(NOW, fixture.email.getRecipientDeliveryUpdatedAt());
        fixture.feedback("<api-id@relay.test>", "spam", NOW.plusSeconds(2));
        assertEquals("REVIEW_REQUIRED_CONFLICT", fixture.email.getStatus());
        assertEquals("SPAM", fixture.email.getRecipientDeliveryStatus());
    }

    @Test void conflictingCallbackFirstEvidenceRequiresExplicitReviewAndLaterCallbacksCannotClearIt() {
        var fixture = new Fixture();
        fixture.sender = fixture.sender(request -> {
            fixture.calls.incrementAndGet(); fixture.feedback("<callback-id@relay.test>", "delivered", NOW);
            return fixture.receipt("<conflicting-api-id@relay.test>");
        });
        var delivery = fixture.delivery(true); delivery.deliver(1L);
        fixture.feedback("callback-id@relay.test", "spam", NOW.plusSeconds(1));
        delivery.deliver(1L);
        assertEquals("REVIEW_REQUIRED_CONFLICT", fixture.email.getStatus()); assertEquals(1, fixture.calls.get());
        assertEquals("callback-id@relay.test", fixture.email.getProviderMessageId());
        assertEquals("SPAM", fixture.email.getRecipientDeliveryStatus()); assertEquals(NOW, fixture.email.getSentAt());
    }

    @Test void onlyExplicitStaffAcceptanceOrSuppressionClearsConflictReview() {
        for (String action : List.of("PROVIDER_ACCEPTED", "SUPPRESS")) {
            var fixture = new Fixture(); fixture.sender = fixture.sender(request -> fixture.receipt("api-id@relay.test"));
            fixture.delivery(true).deliver(1L);
            fixture.feedback("conflicting-callback-id@relay.test", "delivered", NOW);
            assertEquals("REVIEW_REQUIRED_CONFLICT", fixture.email.getStatus());
            String expected = "SUPPRESS".equals(action) ? "SUPPRESSED_ADMIN" : "SENT";
            assertEquals(expected, fixture.review().review(1L, action, "case-123",
                    "Investigated conflicting provider evidence.", true));
            fixture.feedback("api-id@relay.test", "delivered", NOW.plusSeconds(1));
            assertEquals(expected, fixture.email.getStatus());
            assertEquals("api-id@relay.test", fixture.email.getProviderMessageId());
            assertEquals("DELIVERED", fixture.email.getRecipientDeliveryStatus());
            if ("SUPPRESS".equals(action)) {
                fixture.feedback("conflicting-callback-id@relay.test", "spam", NOW.plusSeconds(2));
                assertEquals("SUPPRESSED_ADMIN", fixture.email.getStatus());
                assertEquals("This message was suppressed before further delivery.", fixture.email.getHtmlBody());
            }
        }
    }

    @Test void inFlightConflictingApiReceiptCannotUndoExplicitSuppression() {
        var fixture = new Fixture();
        fixture.sender = fixture.sender(request -> {
            fixture.calls.incrementAndGet();
            fixture.feedback("callback-id@relay.test", "delivered", NOW);
            fixture.feedback("different-callback-id@relay.test", "hard_bounce", NOW.plusSeconds(1));
            assertEquals("REVIEW_REQUIRED_CONFLICT", fixture.email.getStatus());
            fixture.review().review(1L, "SUPPRESS", "", "Investigated the conflicting callback evidence.", false);
            return fixture.receipt("different-api-id@relay.test");
        });
        var delivery = fixture.delivery(true); delivery.deliver(1L); delivery.deliver(1L);
        assertEquals("SUPPRESSED_ADMIN", fixture.email.getStatus()); assertEquals(1, fixture.calls.get());
        assertEquals("callback-id@relay.test", fixture.email.getProviderMessageId());
        assertEquals("DELIVERED", fixture.email.getRecipientDeliveryStatus());
        assertEquals("This message was suppressed before further delivery.", fixture.email.getHtmlBody());
    }

    @Test void wrongRecipientUnknownCorrelationAndOldEventsCannotLatchConflict() {
        var fixture = new Fixture(); fixture.sender = fixture.sender(request -> fixture.receipt("api-id@relay.test"));
        fixture.delivery(true).deliver(1L);
        fixture.feedback("different-id@relay.test", "delivered", NOW, "other@example.invalid", fixture.email.getFeedbackReference());
        fixture.feedback("different-id@relay.test", "delivered", NOW, "recipient@example.invalid", "x".repeat(43));
        fixture.feedback("different-id@relay.test", "delivered", NOW.minusSeconds(311));
        assertEquals("SENT", fixture.email.getStatus());
        assertEquals("api-id@relay.test", fixture.email.getProviderMessageId());
        assertNull(fixture.email.getRecipientDeliveryStatus()); assertNull(fixture.email.getRecipientFeedbackReceivedAt());
    }

    @Test void uncertainApiResponseCannotUndoAuthenticatedAcceptanceThatArrivedInFlight() {
        var fixture = new Fixture();
        fixture.sender = fixture.sender(request -> {
            fixture.calls.incrementAndGet(); fixture.feedback("provider-1@relay.test", "delivered", NOW);
            return CompletableFuture.failedFuture(new java.io.IOException("synthetic sensitive network failure"));
        });
        var delivery = fixture.delivery(true); delivery.deliver(1L); delivery.deliver(1L);
        assertEquals("SENT", fixture.email.getStatus()); assertEquals("DELIVERED", fixture.email.getRecipientDeliveryStatus());
        assertEquals(1, fixture.calls.get());
    }

    @Test void expiryRevocationDueTimeCompletedStatesAndDisabledMailNeverReachApi() {
        for (String suppressed : List.of("SUPPRESSED_EXPIRED", "SUPPRESSED_REVOKED")) {
            var fixture = new Fixture(); fixture.sender = fixture.sender(request -> { fail("Suppressed account email must not reach HTTPS"); return null; });
            when(fixture.eligibility.suppressionStatus(fixture.email)).thenReturn(suppressed);
            fixture.delivery(true).deliver(1L); assertEquals(suppressed, fixture.email.getStatus()); assertEquals(0, fixture.email.getAttempts());
        }
        for (String status : List.of("SENDING", "SENT", "REVIEW_REQUIRED", "REVIEW_REQUIRED_CONFLICT", "SUPPRESSED_ADMIN")) {
            var fixture = new Fixture(); fixture.sender = fixture.sender(request -> { fail("Unqueued email must not reach HTTPS"); return null; });
            fixture.email.setStatus(status); fixture.delivery(true).deliver(1L); assertEquals(0, fixture.email.getAttempts());
        }
        var due = new Fixture(); due.sender = due.sender(request -> { fail("Future email must not reach HTTPS"); return null; });
        due.email.setNextAttemptAt(NOW.plusSeconds(2)); due.delivery(true).deliver(1L); assertEquals(0, due.email.getAttempts());
        var disabled = new Fixture(); disabled.sender = disabled.sender(request -> { fail("Disabled email must not reach HTTPS"); return null; });
        disabled.delivery(false).deliver(1L); verifyNoInteractions(disabled.emails);
    }

    @Test void verificationRecoveryAndDeliveryShareOptionalTransportReadinessWithoutConstructorChanges() {
        var fixture = new Fixture(); fixture.sender = fixture.sender(request -> { fail("Readiness must not send"); return null; });
        fixture.delivery(true);
        var recovery = new AccountRecoveryService(null, null, null, fixture.clock, fixture.provider,
                BrevoTransactionalMailSenderTests.ORIGIN, "approved@example.invalid", true, null);
        var users = new UserService(null, null, null, null, null, null, fixture.provider, fixture.clock,
                BrevoTransactionalMailSenderTests.ORIGIN, "approved@example.invalid", true, null);
        assertTrue(recovery.isConfigured()); assertTrue(users.isEmailVerificationConfigured());
        fixture.sender = new BrevoTransactionalMailSender("", BrevoTransactionalMailSenderTests.ORIGIN,
                request -> { fail("Unconfigured email must not send"); return null; }, Duration.ofSeconds(1));
        fixture.delivery(true);
        assertFalse(recovery.isConfigured()); assertFalse(users.isEmailVerificationConfigured());
        verifyNoInteractions(fixture.emails);
    }

    @Test void conflictReviewRejectsRetryWithRecipientProofAndAllowsExplicitCheckedAcceptance() {
        var fixture = new Fixture(); fixture.email.setStatus("REVIEW_REQUIRED_CONFLICT");
        fixture.email.setProviderMessageId("callback-id"); fixture.email.setRecipientDeliveryStatus("DELIVERED");
        fixture.email.setRecipientFeedbackReceivedAt(NOW);
        var review = fixture.review();
        assertThrows(IllegalStateException.class, () -> review.review(1L, "REQUEUE_NOT_ACCEPTED", "case-123",
                "Investigated conflicting provider evidence.", true));
        assertEquals("REVIEW_REQUIRED_CONFLICT", fixture.email.getStatus());
        assertThrows(IllegalArgumentException.class, () -> review.review(1L, "PROVIDER_ACCEPTED", "case-123",
                "Investigated conflicting provider evidence.", false));
        assertEquals("SENT", review.review(1L, "PROVIDER_ACCEPTED", "case-123", "Investigated conflicting provider evidence.", true));
        assertEquals("callback-id", fixture.email.getProviderMessageId()); assertEquals("DELIVERED", fixture.email.getRecipientDeliveryStatus());
        assertTrue(EmailReviewService.STATUSES.contains("REVIEW_REQUIRED_CONFLICT"));
    }

    static class Fixture {
        final OutboundEmail email = new OutboundEmail();
        final OutboundEmailRepository emails = mock(OutboundEmailRepository.class);
        final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final AccountEmailEligibility eligibility = mock(AccountEmailEligibility.class);
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        JavaMailSender sender;

        Fixture() {
            ReflectionTestUtils.setField(email, "id", 1L);
            email.setEventKey("account:verification:synthetic"); email.setRecipient("recipient@example.invalid");
            email.setSubject("Synthetic verification"); email.setHtmlBody("<p>Local-only synthetic content</p>");
            email.setCreatedAt(NOW.minusSeconds(10)); email.setNextAttemptAt(NOW);
            when(emails.lock(1L)).thenReturn(Optional.of(email));
            when(emails.lockByFeedbackReference(anyString())).thenAnswer(call -> call.getArgument(0).equals(email.getFeedbackReference())
                    ? Optional.of(email) : Optional.empty());
            when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
            doAnswer(call -> { commits.incrementAndGet(); return null; }).when(manager).commit(any());
        }

        BrevoTransactionalMailSender sender(BrevoTransactionalMailSender.Exchange exchange) {
            return new BrevoTransactionalMailSender(BrevoTransactionalMailSenderTests.KEY, BrevoTransactionalMailSenderTests.ORIGIN,
                    exchange, Duration.ofSeconds(1));
        }

        EmailDeliveryService delivery(boolean enabled) {
            when(provider.getIfAvailable()).thenReturn(sender); when(provider.getObject()).thenReturn(sender);
            return new EmailDeliveryService(emails, provider, clock, manager, "approved@example.invalid", enabled, eligibility);
        }

        EmailReviewService review() {
            var access = mock(AdminAccess.class);
            when(access.requireAdministrator("SYSTEM_SETTINGS_MANAGE")).thenReturn(new User());
            return new EmailReviewService(emails, access, eligibility, clock);
        }

        CompletableFuture<BrevoTransactionalMailSender.Response> receipt(String id) {
            return CompletableFuture.completedFuture(new BrevoTransactionalMailSender.Response(201,
                    ("{\"messageId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)));
        }

        void feedback(String id, String event, Instant at) {
            feedback(id, event, at, "recipient@example.invalid", email.getFeedbackReference());
        }

        void feedback(String id, String event, Instant at, String recipient, String reference) {
            var configuration = new EmailFeedbackConfiguration(true, TOKEN, BrevoTransactionalMailSenderTests.ORIGIN);
            new EmailFeedbackService(emails, configuration, clock).receive("Bearer " + TOKEN,
                    ("{\"event\":\"" + event + "\",\"email\":\"" + recipient + "\",\"message-id\":\"" + id
                            + "\",\"ts_event\":" + at.getEpochSecond() + ",\"X-Mailin-custom\":\"travelgo:" + reference + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
        }
    }
}
