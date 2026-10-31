package com.travelgo.service;

import com.travelgo.entity.OutboundEmail;
import com.travelgo.entity.User;
import com.travelgo.repository.OutboundEmailRepository;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/** Tokens and their messages commit together. Account mail remains independent of booking dispatch. */
@Service
public class AccountEmailService {
    public static final String VERIFICATION_PREFIX = "account:verification:";
    public static final String RECOVERY_PREFIX = "account:recovery:";
    private final OutboundEmailRepository emails;
    private final TemplateEngine templates;
    private final AccountEmailDispatchService dispatch;
    private final Clock clock;
    private final String baseUrl;

    public AccountEmailService(OutboundEmailRepository emails, TemplateEngine templates,
            AccountEmailDispatchService dispatch, Clock clock,
            @Value("${travelgo.public-base-url:http://localhost:8081}") String baseUrl) {
        this.emails = emails; this.templates = templates; this.dispatch = dispatch;
        this.clock = clock; this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void verification(User user, String token, String hash) {
        queue(user, VERIFICATION_PREFIX + hash, "Verify your TravelGO account",
                "Verify your email address to activate your TravelGO account.",
                "Verify email address", "/auth/verify-email?token=" + token,
                "This link expires after 24 hours. If you did not create this account, ignore this email.");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recovery(User user, String token, String hash) {
        queue(user, RECOVERY_PREFIX + hash, "Reset your TravelGO password",
                "Use this link to choose a new password for your TravelGO account.",
                "Reset password", "/auth/reset-password?token=" + token,
                "This link expires after 30 minutes and works once. If you did not request this change, ignore this email.");
    }

    private void queue(User user, String key, String subject, String intro,
            String label, String route, String guidance) {
        if (emails.existsByEventKey(key)) return;
        Context context = new Context();
        context.setVariables(Map.of("heading", subject, "customerName", user.getName(),
                "intro", intro, "linkLabel", label, "link", baseUrl + route, "guidance", guidance));
        OutboundEmail email = new OutboundEmail();
        email.setEventKey(key); email.setRecipient(user.getEmail()); email.setSubject(subject);
        email.setHtmlBody(templates.process("email/account", context));
        email.setCreatedAt(clock.instant()); email.setNextAttemptAt(clock.instant());
        emails.saveAndFlush(email);
        Long id = email.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { dispatch.submit(id); }
                catch (RuntimeException unavailable) {
                    // Only durable QUEUED rows are retried; uncertain claims require staff review.
                    org.slf4j.LoggerFactory.getLogger(AccountEmailService.class)
                            .warn("Account email event {} needs a delivery status check", id);
                }
            }
        });
    }
}
