package com.travelgo.service;

import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records evidence only; this service has no payment-provider or bank-transfer client. */
@Service("offlineRefunds")
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class OfflineRefundSettlementService {
    private final OfflineRefundSettlementRepository settlements;
    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final ProviderRefundAttemptRepository providerRefunds;
    private final ProviderCheckoutAttemptRepository checkouts;
    private final WorkflowRules rules;
    private final AuditService audit;
    @PersistenceContext private EntityManager em;
    public OfflineRefundSettlementService(OfflineRefundSettlementRepository settlements, RefundRepository refunds,
            PaymentRepository payments, ProviderRefundAttemptRepository providerRefunds, ProviderCheckoutAttemptRepository checkouts,
            WorkflowRules rules, AuditService audit) {
        this.settlements = settlements; this.refunds = refunds; this.payments = payments; this.providerRefunds = providerRefunds;
        this.checkouts = checkouts; this.rules = rules; this.audit = audit;
    }
    @Transactional(readOnly = true)
    public List<OfflineRefundSettlement> recentSettlements() { officer(false); return settlements.findTop50ByOrderByRecordedAtDescIdDesc(); }
    /** Advisory UI state. The mutation repeats every check under current database locks. */
    @Transactional(readOnly = true)
    public boolean canRecord(Refund refund) {
        return refund != null && refund.getPayment() != null && channel(refund) != null
                && refund.getStatus() == RefundStatus.REFUND_PENDING && refund.getPayment().getPaymentStatus() == PaymentStatus.PAID
                && refund.getPayment().getBooking().getBookingStatus() == BookingStatus.CANCELLED;
    }
    public String channel(Refund refund) {
        if (refund == null || refund.getPayment() == null || refund.getPayment().getPaymentMethod() == null) return null;
        String method = refund.getPayment().getPaymentMethod().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (method) { case "CASH", "CASH_DEPOSIT" -> "CASH"; case "BANK_TRANSFER" -> "BANK_TRANSFER"; default -> null; };
    }
    public OfflineRefundSettlement record(Long refundId, String selectedChannel, String reference, BigDecimal verifiedAmount,
            String currency, LocalDateTime reimbursedAt, String evidence, boolean verified) {
        User actor = officer(true);
        if (!verified) throw new IllegalArgumentException("Verify the completed reimbursement before recording evidence.");
        String normalizedReference = reference(reference);
        String source = evidence(evidence);
        rules.money(verifiedAmount, false);
        if (!"USD".equals(currency)) throw new IllegalArgumentException("Record the original USD refund amount. Currency conversion is not supported.");
        Refund initial = refunds.findById(refundId).orElseThrow(() -> new IllegalArgumentException("Refund not found."));
        Booking booking = rules.lock(initial.getPayment().getBooking().getId());
        Refund refund = freshRefund(refundId);
        Payment payment = freshPayment(refund.getPayment().getId());
        refund.setPayment(payment);
        em.unwrap(org.hibernate.Session.class).setReadOnly(refund, false);
        em.unwrap(org.hibernate.Session.class).setReadOnly(payment, false);
        if (!booking.getId().equals(payment.getBooking().getId()) || booking.getBookingStatus() != BookingStatus.CANCELLED)
            throw new IllegalStateException("Only a refund on a cancelled booking can be settled offline.");
        String expectedChannel = channel(refund);
        if (expectedChannel == null || !expectedChannel.equals(selectedChannel))
            throw new IllegalArgumentException("Use the original cash or bank-transfer channel. Provider and simulated refunds cannot be settled here.");
        if (payment.getPaymentMethod().length() > 100) throw new IllegalStateException("The original payment method requires review.");
        String originalReference = payment.getTransactionReference();
        if (originalReference == null || originalReference.isBlank() || originalReference.length() > 200
                || originalReference.chars().anyMatch(Character::isISOControl) || providerReference(originalReference))
            throw new IllegalStateException("The original offline payment reference requires review before refund settlement.");
        if (providerRefunds.findByRefund_Id(refundId).isPresent() || checkouts.findByBooking_IdAndPaymentTypeOrderByIdDesc(booking.getId(), payment.getPaymentType())
                .stream().anyMatch(a -> a.getSettledPayment() != null && payment.getId().equals(a.getSettledPayment().getId())))
            throw new IllegalStateException("Provider-linked refunds must be reconciled through their original provider.");
        rules.money(refund.getAmount(), false); rules.money(payment.getAmount(), false);
        if (refund.getAmount().compareTo(payment.getAmount()) > 0 || refund.getAmount().compareTo(verifiedAmount) != 0)
            throw new IllegalArgumentException("The evidence amount must exactly match the stored refund and cannot exceed the original payment.");
        if (payment.getPaidAt() == null || reimbursedAt == null || reimbursedAt.isBefore(payment.getPaidAt()) || reimbursedAt.isAfter(rules.now()))
            throw new IllegalArgumentException("Enter the completed reimbursement time, after original payment and no later than now.");
        if (reimbursedAt.getNano() % 1000 != 0) throw new IllegalArgumentException("Use a reimbursement time with no more than six fractional-second digits.");
        var existing = freshSettlement(refundId);
        if (existing.isPresent()) {
            OfflineRefundSettlement old = existing.get();
            if (refund.getStatus() != RefundStatus.REFUND_PROCESSED || !Objects.equals(refund.getProcessedAt(), old.getReimbursedAt())
                    || !Objects.equals(refund.getSettlementReference(), ledgerReference(old.getChannel(), old.getReference()))
                    || refund.getProcessedBy() == null || !refund.getProcessedBy().getId().equals(old.getRecordedBy().getId())
                    || old.getAmount().compareTo(verifiedAmount) != 0 || !old.getCurrency().equals(currency)
                    || !old.getChannel().equals(selectedChannel) || !old.getReference().equals(normalizedReference)
                    || !old.getEvidenceSource().equals(source) || !old.getReimbursedAt().equals(reimbursedAt)
                    || !old.getOriginalPaymentMethod().equals(payment.getPaymentMethod()) || !old.getOriginalPaymentReference().equals(originalReference)
                    || old.getOriginalPaymentAmount().compareTo(payment.getAmount()) != 0
                    || payment.getPaymentStatus() != (refund.getAmount().compareTo(payment.getAmount()) == 0 ? PaymentStatus.REFUNDED : PaymentStatus.PAID))
                throw new IllegalStateException("This refund already has different settlement evidence. Review the saved record; it will not be overwritten.");
            return old;
        }
        if (refund.getStatus() != RefundStatus.REFUND_PENDING || payment.getPaymentStatus() != PaymentStatus.PAID
                || refund.getProcessedAt() != null || refund.getSettlementReference() != null || refund.getProcessedBy() != null)
            throw new IllegalStateException("Only an unpaid pending refund with an unchanged paid original transaction can be settled here.");
        if (settlements.existsByChannelAndReference(selectedChannel, normalizedReference))
            throw new IllegalStateException("This reimbursement reference is already recorded against another refund.");
        OfflineRefundSettlement settlement = new OfflineRefundSettlement(refund, selectedChannel, normalizedReference,
                refund.getAmount(), "USD", source, reimbursedAt, rules.now(), actor);
        settlements.saveAndFlush(settlement); // Unique constraints reject concurrent evidence reuse before changing the ledger.
        refund.setStatus(RefundStatus.REFUND_PROCESSED); refund.setProcessedAt(reimbursedAt);
        refund.setSettlementReference(ledgerReference(selectedChannel, normalizedReference)); refund.setProcessedBy(actor); refunds.saveAndFlush(refund);
        if (refund.getAmount().compareTo(payment.getAmount()) == 0) { payment.setPaymentStatus(PaymentStatus.REFUNDED); payments.saveAndFlush(payment); }
        audit.logAction("OFFLINE_REFUND_SETTLEMENT_RECORDED", "Refund", refundId,
                "Verified already-completed " + selectedChannel + " reimbursement; " + refund.getAmount().toPlainString()
                + " USD; reference: " + normalizedReference + "; reimbursed: " + reimbursedAt + "; evidence: " + source
                + ". Evidence recorded only; TravelGO initiated no transfer.");
        rules.notify(booking.getUser(), "REFUND_RECORDED", "Offline refund recorded",
                "The agency recorded a completed USD " + refund.getAmount().toPlainString() + " refund for booking #" + booking.getId()
                + " by " + (selectedChannel.equals("CASH") ? "cash" : "bank transfer") + ". Reference: " + normalizedReference
                + ". Contact the agency if you need help tracing the reimbursement.", "BOOKING", booking.getId());
        return settlement;
    }
    private Refund freshRefund(Long id) {
        var locked = refunds.lockById(id).orElseThrow(() -> new IllegalArgumentException("Refund not found."));
        em.flush(); em.detach(locked); return refunds.lockById(id).orElseThrow();
    }
    private Payment freshPayment(Long id) {
        var locked = payments.lockById(id).orElseThrow(() -> new IllegalStateException("Original payment is unavailable."));
        em.flush(); em.detach(locked); return payments.lockById(id).orElseThrow();
    }
    private Optional<OfflineRefundSettlement> freshSettlement(Long refundId) {
        var value = settlements.lockByRefundId(refundId);
        value.ifPresent(s -> { em.flush(); em.detach(s); });
        return value.isPresent() ? settlements.lockByRefundId(refundId) : value;
    }
    private User officer(boolean manage) {
        User actor = rules.actor();
        Set<String> allowed = manage ? Set.of("VISA_MANAGE", "PAYMENT_MANAGE") : Set.of("VISA_MANAGE", "PAYMENT_VIEW", "PAYMENT_MANAGE");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!rules.role(actor, "VISA_OFFICER") || actor.getRole().getPermissions().stream().noneMatch(p -> allowed.contains(p.getPermissionName())
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("PERM_" + p.getPermissionName()))))
            throw new AccessDeniedException("Visa officer payment permission is required.");
        return actor;
    }
    private static boolean providerReference(String reference) {
        String ref = reference.strip().toUpperCase(Locale.ROOT);
        return ref.startsWith("PH-") || ref.startsWith("CS_") || ref.startsWith("PI_");
    }
    private static String reference(String value) {
        if (value != null && value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Reimbursement references cannot contain control characters.");
        String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z0-9][A-Z0-9./:_-]{2,119}"))
            throw new IllegalArgumentException("Enter a unique receipt or bank reference (3–120 letters, digits, dots, slashes, colons, underscores or hyphens).");
        return result;
    }
    private static String evidence(String value) {
        if (value != null && value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw new IllegalArgumentException("Payout evidence cannot contain unsupported control characters.");
        String result = value == null ? "" : value.trim();
        if (result.length() < 20 || result.length() > 1000 || result.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw new IllegalArgumentException("Record the reviewed payout evidence source (20–1,000 characters). Do not include full bank account or card details.");
        return result;
    }
    private static String ledgerReference(String channel, String reference) { return "OFFLINE-" + channel + "-" + reference; }
}
