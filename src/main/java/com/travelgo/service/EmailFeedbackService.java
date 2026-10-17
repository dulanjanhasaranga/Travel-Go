package com.travelgo.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelgo.config.EmailFeedbackConfiguration;
import com.travelgo.entity.OutboundEmail;
import com.travelgo.repository.OutboundEmailRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Recipient outcomes are separate from SMTP transport acceptance; callbacks never resend mail. */
@Service
public class EmailFeedbackService {
    public static final int MAX_PAYLOAD_BYTES = 16384;
    private static final Map<String, String> EVENTS = Map.of(
            "delivered", "DELIVERED", "deferred", "DEFERRED", "soft_bounce", "SOFT_BOUNCE",
            "hard_bounce", "HARD_BOUNCE", "blocked", "BLOCKED", "invalid_email", "INVALID",
            "spam", "SPAM", "unsubscribed", "UNSUBSCRIBED");
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                .maxStringLength(2048).maxNumberLength(30).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final OutboundEmailRepository emails;
    private final EmailFeedbackConfiguration configuration;
    private final Clock clock;

    public EmailFeedbackService(OutboundEmailRepository emails, EmailFeedbackConfiguration configuration, Clock clock) {
        this.emails = emails; this.configuration = configuration; this.clock = clock;
    }

    @Transactional
    public void receive(String authorization, byte[] payload) {
        configuration.authenticate(authorization);
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) throw invalid();
        JsonNode event;
        try {
            event = mapper.readTree(payload);
            if (event == null || !event.isObject() || event.size() > 40) throw invalid();
        } catch (java.io.IOException malformed) { throw invalid(); }
        String providerEvent = text(event, "event", 30);
        // Open/click tracking is intentionally not collected, and batched payloads are not supported.
        String outcome = EVENTS.get(providerEvent);
        if (outcome == null) return;
        String custom = text(event, "X-Mailin-custom", 100);
        if (!custom.matches("travelgo:[A-Za-z0-9_-]{43}")) throw invalid();
        String recipient = text(event, "email", 150);
        if (!recipient.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw invalid();
        String providerMessage;
        try { providerMessage = com.travelgo.util.ProviderMessageIds.normalize(text(event, "message-id", 255)); }
        catch (IllegalArgumentException malformedId) { throw invalid(); }
        JsonNode timestamp = event.get("ts_event");
        if (timestamp == null || !timestamp.isIntegralNumber() || !timestamp.canConvertToLong()) throw invalid();
        Instant occurred;
        try { occurred = Instant.ofEpochSecond(timestamp.longValue()); }
        catch (java.time.DateTimeException invalidTime) { throw invalid(); }
        if (occurred.isAfter(clock.instant().plusSeconds(300))) throw invalid();
        OutboundEmail email = emails.lockByFeedbackReference(custom.substring("travelgo:".length())).orElse(null);
        // Return the same response for unknown correlation, different recipient or another environment.
        if (email == null || !custom.substring("travelgo:".length()).equals(email.getFeedbackReference())
                || !email.getRecipient().equalsIgnoreCase(recipient)) return;
        String transportStatus = email.getStatus();
        boolean requeuedClaim = "QUEUED".equals(transportStatus) && email.getAttempts() > 0;
        boolean suppressedClaim = "SUPPRESSED_ADMIN".equals(transportStatus) && email.getAttempts() > 0;
        if (!java.util.List.of("SENDING", "SENT", "REVIEW_REQUIRED", "REVIEW_REQUIRED_CONFLICT").contains(transportStatus)
                && !requeuedClaim && !suppressedClaim) return;
        if (email.getCreatedAt() == null || occurred.isBefore(email.getCreatedAt().minusSeconds(300))) return;
        if (email.getProviderMessageId() != null
                && !com.travelgo.util.ProviderMessageIds.same(email.getProviderMessageId(), providerMessage)) {
            // The API receipt and authenticated callback can reach this lock in either order.
            // Keep the first identifier and outcome, while retaining proof that a provider
            // event reached this claim. It must never be used to authorize another send.
            if (!suppressedClaim) email.setStatus("REVIEW_REQUIRED_CONFLICT");
            if (email.getRecipientFeedbackReceivedAt() == null) email.setRecipientFeedbackReceivedAt(clock.instant());
            return;
        }
        // Conflicting evidence stays in review until an administrator records acceptance
        // or suppression, even when later callbacks match the retained identifier.
        boolean conflictingAcceptance = "REVIEW_REQUIRED_CONFLICT".equals(transportStatus);
        if (!advances(email, outcome, occurred)) return;
        email.setProviderMessageId(providerMessage);
        email.setRecipientDeliveryStatus(outcome);
        email.setRecipientDeliveryUpdatedAt(occurred);
        email.setRecipientFeedbackReceivedAt(clock.instant());
        // A genuine delivery result also settles an ambiguous SMTP acknowledgement.
        // A late result may beat an approved retry to this row lock. Settle it before another claim.
        // Suppression stops further sends; it cannot recall a previously accepted message.
        if (!suppressedClaim && !conflictingAcceptance) email.setStatus("SENT");
        if (email.getSentAt() == null) email.setSentAt(clock.instant());
    }

    private boolean advances(OutboundEmail email, String next, Instant at) {
        String previous = email.getRecipientDeliveryStatus();
        Instant previousAt = email.getRecipientDeliveryUpdatedAt();
        if (previous == null || previousAt == null) return true;
        if (at.isBefore(previousAt)) return false;
        if (at.equals(previousAt)) return priority(next) > priority(previous);
        // Temporary events never undo a recipient-server acceptance, and terminal failures remain final.
        if (priority(previous) >= 4 && priority(next) < priority(previous)) return false;
        if ("DELIVERED".equals(previous) && priority(next) < 3) return false;
        return true;
    }
    private int priority(String outcome) {
        return switch (outcome) {
            case "DEFERRED" -> 1; case "SOFT_BOUNCE" -> 2; case "DELIVERED" -> 3;
            case "HARD_BOUNCE", "BLOCKED", "INVALID" -> 4;
            case "UNSUBSCRIBED" -> 5; case "SPAM" -> 6; default -> 0;
        };
    }
    private String text(JsonNode event, String name, int maximum) {
        JsonNode value = event.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maximum)
            throw invalid();
        return value.textValue();
    }
    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email feedback payload.");
    }
}
