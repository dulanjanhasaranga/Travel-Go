package com.travelgo.service;

import com.travelgo.entity.OutboundEmail;
import com.travelgo.entity.User;
import com.travelgo.repository.EmailVerificationTokenRepository;
import com.travelgo.repository.PasswordResetRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** Called in the fresh claim transaction, before any SMTP operation. */
@Service
public class AccountEmailEligibility {
    private final EmailVerificationTokenRepository verificationTokens;
    private final PasswordResetRepository resets;
    private final Clock clock;
    public AccountEmailEligibility(EmailVerificationTokenRepository verificationTokens,
            PasswordResetRepository resets, Clock clock) {
        this.verificationTokens = verificationTokens; this.resets = resets; this.clock = clock;
    }
    public String suppressionStatus(OutboundEmail email) {
        String key = email.getEventKey();
        if (key.startsWith(AccountEmailService.VERIFICATION_PREFIX)) {
            var found = verificationTokens.findById(key.substring(AccountEmailService.VERIFICATION_PREFIX.length()));
            if (found.isEmpty()) return "SUPPRESSED_REVOKED";
            var token = found.get();
            if (!clock.instant().isBefore(token.getExpiresAt())) return "SUPPRESSED_EXPIRED";
            User user = token.getUser();
            return token.isUsed() || !eligibleRecipient(user, email) || user.isActive()
                    || user.getRole() == null || !"CUSTOMER".equals(user.getRole().getRoleName())
                    ? "SUPPRESSED_REVOKED" : null;
        }
        if (key.startsWith(AccountEmailService.RECOVERY_PREFIX)) {
            var found = resets.findById(key.substring(AccountEmailService.RECOVERY_PREFIX.length()));
            if (found.isEmpty()) return "SUPPRESSED_REVOKED";
            var token = found.get();
            if (!clock.instant().isBefore(token.getExpiresAt())) return "SUPPRESSED_EXPIRED";
            User user = token.getUser();
            return token.isUsed() || !eligibleRecipient(user, email) || !user.isActive()
                    || !token.getPasswordAtIssue().equals(user.getPassword()) ? "SUPPRESSED_REVOKED" : null;
        }
        return key.startsWith("account:") ? "SUPPRESSED_REVOKED" : null;
    }
    private boolean eligibleRecipient(User user, OutboundEmail email) {
        return !user.isDemoAccount() && user.getEmail().equalsIgnoreCase(email.getRecipient());
    }
}
