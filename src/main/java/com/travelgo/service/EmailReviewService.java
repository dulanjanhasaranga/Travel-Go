package com.travelgo.service;

import com.travelgo.entity.OutboundEmail;
import com.travelgo.repository.OutboundEmailRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrator review of uncertain transport results. No action sends email directly. */
@Service
public class EmailReviewService {
    public static final List<String> STATUSES = List.of("QUEUED", "SENDING", "REVIEW_REQUIRED", "REVIEW_REQUIRED_CONFLICT", "SENT",
            "SUPPRESSED_EXPIRED", "SUPPRESSED_REVOKED", "SUPPRESSED_ADMIN");
    private final OutboundEmailRepository emails;
    private final AdminAccess access;
    private final AccountEmailEligibility eligibility;
    private final Clock clock;

    public EmailReviewService(OutboundEmailRepository emails, AdminAccess access,
            AccountEmailEligibility eligibility, Clock clock) {
        this.emails = emails; this.access = access; this.eligibility = eligibility; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<MessageSummary> findPage(String status, int page) {
        access.requireAdministrator("SYSTEM_SETTINGS_VIEW", "SYSTEM_SETTINGS_MANAGE");
        if (status != null && !status.isBlank() && !STATUSES.contains(status))
            throw new IllegalArgumentException("Choose a valid delivery status.");
        if (page < 0 || page > 100000) throw new IllegalArgumentException("Choose a valid page.");
        return emails.findDeliveryMetadata(status == null || status.isBlank() ? null : status,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "id")))
                .map(e -> new MessageSummary(e.getId(), category(e.getEventKey()), e.getRecipient(), e.getSubject(),
                        e.getStatus(), e.getCreatedAt(), e.getSentAt(), e.getAttempts(),
                        e.getRecipientDeliveryStatus(), e.getRecipientDeliveryUpdatedAt(), e.getRecipientFeedbackReceivedAt(),
                        e.getProviderMessageId()));
    }

    @Transactional
    public String review(Long id, String action, String evidenceReference, String note, boolean providerChecked) {
        var actor = access.requireAdministrator("SYSTEM_SETTINGS_MANAGE");
        if (!List.of("SUPPRESS", "PROVIDER_ACCEPTED", "REQUEUE_NOT_ACCEPTED").contains(action == null ? "" : action))
            throw new IllegalArgumentException("Choose a valid delivery action.");
        String reviewNote = note == null ? "" : note.trim();
        if (reviewNote.length() < 10 || reviewNote.length() > 1000 || reviewNote.codePoints()
                .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw new IllegalArgumentException("Enter the review outcome, between 10 and 1,000 characters.");
        String reference = evidenceReference == null ? "" : evidenceReference.trim();
        if (!"SUPPRESS".equals(action) && (!providerChecked || reference.length() < 3 || reference.length() > 150
                || !reference.matches("[A-Za-z0-9][A-Za-z0-9._:/ -]*")))
            throw new IllegalArgumentException("Check the provider record and enter its reference before recording an outcome.");
        OutboundEmail email = emails.lock(id).orElseThrow(() -> new IllegalArgumentException("Email record not found."));
        String previous = email.getStatus();
        // A live claim may be waiting for SMTP or committing its result. Never edit or resend it here.
        if ("SENDING".equals(previous))
            throw new IllegalStateException("This message has a delivery claim. Ask the application operator to investigate before taking further action.");
        if ("SUPPRESS".equals(action)) {
            if ("SUPPRESSED_ADMIN".equals(previous)) return previous;
            if (!"QUEUED".equals(previous) && !"REVIEW_REQUIRED".equals(previous) && !"REVIEW_REQUIRED_CONFLICT".equals(previous))
                throw new IllegalStateException("Only queued messages or uncertain sends can be suppressed.");
            suppress(email, "SUPPRESSED_ADMIN");
        } else if ("PROVIDER_ACCEPTED".equals(action)) {
            if ("SENT".equals(previous)) return previous;
            requireUncertain(previous);
            email.setStatus("SENT"); email.setSentAt(clock.instant());
        } else {
            // Duplicate requests may see the same row still queued; do not create another claim or audit event.
            if ("QUEUED".equals(previous)) return previous;
            requireUncertain(previous);
            if (email.getRecipientFeedbackReceivedAt() != null || email.getRecipientDeliveryStatus() != null)
                throw new IllegalStateException("Authenticated provider feedback already proves acceptance. Record acceptance or suppress this message after investigating the conflicting evidence.");
            String suppressed = email.getEventKey().startsWith("account:") ? eligibility.suppressionStatus(email) : null;
            if (suppressed != null) suppress(email, suppressed);
            else { email.setStatus("QUEUED"); email.setNextAttemptAt(clock.instant()); }
        }
        access.record(actor, "EMAIL_DELIVERY_REVIEW", "OutboundEmail", id, "Action=" + action + "; "
                + previous + " -> " + email.getStatus() + "; provider reference="
                + (reference.isEmpty() ? "not applicable" : reference) + "; " + reviewNote.replaceAll("[\\r\\n\\t]+", " "));
        return email.getStatus();
    }

    private void requireUncertain(String status) {
        if (!"REVIEW_REQUIRED".equals(status) && !"REVIEW_REQUIRED_CONFLICT".equals(status))
            throw new IllegalStateException("This outcome can only be recorded for a message awaiting delivery review.");
    }
    private void suppress(OutboundEmail email, String status) {
        email.setStatus(status); email.setHtmlBody("This message was suppressed before further delivery.");
        email.setAttachmentName(null); email.setAttachmentData(null);
    }
    private String category(String key) {
        if (key.startsWith(AccountEmailService.VERIFICATION_PREFIX)) return "Account verification";
        if (key.startsWith(AccountEmailService.RECOVERY_PREFIX)) return "Password recovery";
        if (key.startsWith("booking:")) return "Booking request";
        if (key.startsWith("cancellation:")) return "Booking cancellation";
        if (key.startsWith("payment:")) return "Payment confirmation";
        if (key.startsWith("visaUpdate:")) return "Visa update";
        return "Customer message";
    }
    public record MessageSummary(Long id, String category, String recipient, String subject, String status,
            Instant createdAt, Instant sentAt, int attempts, String recipientDeliveryStatus, Instant recipientDeliveryUpdatedAt,
            Instant recipientFeedbackReceivedAt, String providerMessageId) {
        public MessageSummary(Long id, String category, String recipient, String subject, String status,
                Instant createdAt, Instant sentAt, int attempts, String recipientDeliveryStatus, Instant recipientDeliveryUpdatedAt) {
            this(id, category, recipient, subject, status, createdAt, sentAt, attempts,
                    recipientDeliveryStatus, recipientDeliveryUpdatedAt, null, null);
        }
        public MessageSummary(Long id, String category, String recipient, String subject, String status,
                Instant createdAt, Instant sentAt, int attempts, String recipientDeliveryStatus, Instant recipientDeliveryUpdatedAt,
                Instant recipientFeedbackReceivedAt) {
            this(id, category, recipient, subject, status, createdAt, sentAt, attempts,
                    recipientDeliveryStatus, recipientDeliveryUpdatedAt, recipientFeedbackReceivedAt, null);
        }
        public String recipientDeliveryLabel() {
            if (recipientDeliveryStatus == null) return "Not reported";
            return switch (recipientDeliveryStatus) {
                case "DELIVERED" -> "Recipient server accepted";
                case "DEFERRED" -> "Provider delivery delayed";
                case "SOFT_BOUNCE" -> "Temporary delivery failure";
                case "HARD_BOUNCE" -> "Permanent delivery failure";
                case "BLOCKED" -> "Blocked by provider";
                case "INVALID" -> "Invalid recipient";
                case "SPAM" -> "Spam complaint";
                case "UNSUBSCRIBED" -> "Recipient unsubscribed";
                default -> "Not reported";
            };
        }
        public String messageId() {
            try { return com.travelgo.util.ProviderMessageIds.normalize(providerMessageId); }
            catch (IllegalArgumentException invalid) { return "Not reported"; }
        }
        public String travelGoReference() { return "TG-MAIL-" + id; }
        public String statusLabel() {
            return switch (status) {
                case "QUEUED" -> "Queued"; case "SENDING" -> "Delivery claim";
                case "REVIEW_REQUIRED" -> "Review required"; case "SENT" -> "Transport accepted";
                case "REVIEW_REQUIRED_CONFLICT" -> "Review required: conflicting provider evidence";
                case "SUPPRESSED_EXPIRED" -> "Expired link"; case "SUPPRESSED_REVOKED" -> "Revoked link";
                case "SUPPRESSED_ADMIN" -> "Suppressed by administrator"; default -> "Unknown status";
            };
        }
        public boolean reviewable() { return "QUEUED".equals(status) || uncertain(); }
        public boolean uncertain() { return "REVIEW_REQUIRED".equals(status) || "REVIEW_REQUIRED_CONFLICT".equals(status); }
        public boolean retryable() { return uncertain() && recipientDeliveryStatus == null && recipientFeedbackReceivedAt == null; }
    }
}
