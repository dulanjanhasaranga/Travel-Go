package com.travelgo.service;

import com.travelgo.entity.OutboundEmail;
import java.util.OptionalLong;
import jakarta.mail.MessagingException;
import org.springframework.mail.MailSendException;

/** Retry only when the SMTP connection never opened, so no message could be accepted. */
public final class AccountMailRetryPolicy {
    private AccountMailRetryPolicy() { }

    public static OptionalLong retryDelaySeconds(OutboundEmail email, Exception failure) {
        // JavaMailSenderImpl wraps connectTransport failures with this direct cause.
        // Generic send, TLS/authentication and socket timeout exceptions are deliberately
        // excluded: a timeout after DATA can occur after the server accepted the message.
        if (email.getEventKey() != null && email.getEventKey().startsWith("account:")
                && email.getAttempts() > 0 && email.getAttempts() < 3
                && failure instanceof MailSendException sendFailure
                && sendFailure.getCause() instanceof MessagingException
                // Angus is a runtime SMTP provider, rather than a compile-time API dependency.
                && sendFailure.getCause().getClass().getName().equals("org.eclipse.angus.mail.util.MailConnectException")) {
            return OptionalLong.of(email.getAttempts() == 1 ? 2 : 5);
        }
        return OptionalLong.empty();
    }
}
