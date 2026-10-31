package com.travelgo.util;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Configuration readiness only; provider delivery still needs deployment verification. */
public final class MailConfigurationStatus {
    private MailConfigurationStatus() { }
    public static boolean isConfigured(boolean enabled, JavaMailSender sender) {
        if (!enabled || sender == null) return false;
        if (sender instanceof com.travelgo.service.TransactionalMailSender transport) return transport.isConfigured();
        if (sender instanceof JavaMailSenderImpl smtp) {
            return smtp.getHost() != null && !smtp.getHost().isBlank() && smtp.getPort() > 0;
        }
        return true; // An explicitly supplied transport (including isolated test doubles).
    }
}
