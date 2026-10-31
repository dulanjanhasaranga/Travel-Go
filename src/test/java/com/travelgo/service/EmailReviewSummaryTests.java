package com.travelgo.service;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmailReviewSummaryTests {
    private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");

    @Test void actualProviderIdentifierIsNormalizedAndSeparateFromTravelGoReference() {
        var summary = new EmailReviewService.MessageSummary(42L, "Customer message", "recipient@example.invalid", "Synthetic subject",
                "SENT", NOW, NOW, 1, null, null, null, "<actual-provider-id@relay.test>");
        assertEquals("actual-provider-id@relay.test", summary.messageId());
        assertEquals("TG-MAIL-42", summary.travelGoReference());
        assertNotEquals(summary.travelGoReference(), summary.messageId());
    }

    @Test void absentOrInvalidProviderIdentifierIsNotInventedFromTheOutboxRow() {
        for (String providerId : new String[] {null, "", "<>", "unsafe\r\nvalue"}) {
            var summary = new EmailReviewService.MessageSummary(42L, "Customer message", "recipient@example.invalid", "Synthetic subject",
                    "REVIEW_REQUIRED", NOW, null, 1, null, null, null, providerId);
            assertEquals("Not reported", summary.messageId());
            assertEquals("TG-MAIL-42", summary.travelGoReference());
        }
    }

    @Test void legacyConstructorsPreserveUnknownProviderIdAndReceiptBasedRetryEligibility() {
        var withoutReceipt = new EmailReviewService.MessageSummary(42L, "Customer message", "recipient@example.invalid", "Synthetic subject",
                "REVIEW_REQUIRED", NOW, null, 1, null, null);
        var withReceipt = new EmailReviewService.MessageSummary(42L, "Customer message", "recipient@example.invalid", "Synthetic subject",
                "REVIEW_REQUIRED_CONFLICT", NOW, NOW, 1, null, null, NOW);
        assertEquals("Not reported", withoutReceipt.messageId()); assertTrue(withoutReceipt.retryable());
        assertEquals("Not reported", withReceipt.messageId()); assertFalse(withReceipt.retryable());
    }
}
