package com.travelgo.service;

import com.travelgo.entity.OutboundEmail;
import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.util.MailConfigurationStatus;
import com.travelgo.util.ProviderMessageIds;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailDeliveryService {
    private final OutboundEmailRepository emails;
    private final ObjectProvider<JavaMailSender> sender;
    private final Clock clock;
    private final String from;
    private final boolean enabled;
    private final TransactionTemplate tx;
    private final AccountEmailEligibility accountEligibility;

    public EmailDeliveryService(OutboundEmailRepository emails, ObjectProvider<JavaMailSender> sender, Clock clock,
            PlatformTransactionManager manager, String from, boolean enabled) {
        this(emails, sender, clock, manager, from, enabled, null);
    }

    @Autowired
    public EmailDeliveryService(OutboundEmailRepository emails, ObjectProvider<JavaMailSender> sender, Clock clock,
            PlatformTransactionManager manager, @Value("${travelgo.mail.from:no-reply@travelgo.example}") String from,
            @Value("${travelgo.mail.enabled:false}") boolean enabled, AccountEmailEligibility accountEligibility) {
        this.emails = emails; this.sender = sender; this.clock = clock; this.from = from;
        this.enabled = enabled; this.accountEligibility = accountEligibility;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean isConfigured() { return MailConfigurationStatus.isConfigured(enabled, sender.getIfAvailable()); }

    public void deliver(Long id) {
        JavaMailSender mail = sender.getIfAvailable();
        if (!MailConfigurationStatus.isConfigured(enabled, mail)) return;
        OutboundEmail email = tx.execute(s -> {
            var e = emails.lock(id).orElseThrow();
            if (!"QUEUED".equals(e.getStatus()) || e.getNextAttemptAt().isAfter(clock.instant())) return null;
            if (e.getEventKey().startsWith("account:")) {
                String suppressed = accountEligibility == null ? "SUPPRESSED_REVOKED" : accountEligibility.suppressionStatus(e);
                if (suppressed != null) {
                    e.setStatus(suppressed); e.setHtmlBody("Account link expired or revoked before delivery."); return null;
                }
            }
            byte[] referenceBytes = new byte[32]; new java.security.SecureRandom().nextBytes(referenceBytes);
            e.setFeedbackReference(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(referenceBytes));
            e.setRecipientDeliveryStatus(null); e.setRecipientDeliveryUpdatedAt(null);
            e.setRecipientFeedbackReceivedAt(null); e.setProviderMessageId(null);
            e.setStatus("SENDING"); e.setAttempts(e.getAttempts() + 1);
            return e;
        });
        if (email == null) return;
        String claimReference = email.getFeedbackReference();
        // Commit the claim before either transport. A crash never grants permission to resend.
        try {
            String providerMessageId = mail instanceof TransactionalMailSender transport
                    ? ProviderMessageIds.normalize(transport.sendTransactional(email, from)) : sendSmtp(mail, email);
            Boolean conflict = tx.execute(s -> {
                var e = emails.lock(id).orElseThrow();
                if (!claimReference.equals(e.getFeedbackReference())) return false;
                if (providerMessageId != null) {
                    // A verified webhook can beat the API response to this lock. Retain its evidence.
                    if (e.getProviderMessageId() != null && !ProviderMessageIds.same(e.getProviderMessageId(), providerMessageId)) {
                        // Explicit suppression remains final even if the in-flight receipt is contradictory.
                        if ("SUPPRESSED_ADMIN".equals(e.getStatus())) return false;
                        e.setStatus("REVIEW_REQUIRED_CONFLICT"); return true;
                    }
                    e.setProviderMessageId(providerMessageId);
                }
                if ("SENDING".equals(e.getStatus())) e.setStatus("SENT");
                if (e.getSentAt() == null) e.setSentAt(clock.instant());
                return false;
            });
            if (Boolean.TRUE.equals(conflict)) reviewWarning(email.getId());
        } catch (Exception failure) {
            // Only the established positive SMTP pre-connection failure is safe to retry.
            // HTTPS exceptions, rejects, timeouts and malformed receipts always require review.
            Boolean reviewRequired = tx.execute(s -> {
                var e = emails.lock(id).orElseThrow();
                if (!claimReference.equals(e.getFeedbackReference())) return false;
                // A verified callback may already prove acceptance while the send was in flight.
                if ("SENDING".equals(e.getStatus())) {
                    var delay = mail instanceof TransactionalMailSender ? java.util.OptionalLong.empty()
                            : AccountMailRetryPolicy.retryDelaySeconds(e, failure);
                    if (delay.isPresent()) {
                        e.setStatus("QUEUED"); e.setNextAttemptAt(clock.instant().plusSeconds(delay.getAsLong()));
                    } else e.setStatus("REVIEW_REQUIRED");
                }
                return "REVIEW_REQUIRED".equals(e.getStatus()) || "REVIEW_REQUIRED_CONFLICT".equals(e.getStatus());
            });
            if (Boolean.TRUE.equals(reviewRequired)) reviewWarning(email.getId());
        }
    }

    private String sendSmtp(JavaMailSender mail, OutboundEmail email) throws jakarta.mail.MessagingException {
        var message = mail.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from); helper.setTo(email.getRecipient()); helper.setSubject(email.getSubject());
        helper.setText(email.getHtmlBody(), true);
        helper.addInline("travelgo-logo", new ClassPathResource("static/images/brand/travelgo-airborne.png"), "image/png");
        if (email.getAttachmentName() != null && email.getAttachmentData() != null)
            helper.addAttachment(email.getAttachmentName(), new ByteArrayResource(email.getAttachmentData()));
        message.saveChanges(); message.setHeader("Message-ID", "<travelgo-" + email.getId() + "@notifications.travelgo>");
        message.setHeader("X-Mailin-custom", "travelgo:" + email.getFeedbackReference());
        mail.send(message);
        return null; // SMTP provides no authoritative provider message ID here.
    }

    private void reviewWarning(Long id) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Email event {} requires delivery review", id);
    }
}
