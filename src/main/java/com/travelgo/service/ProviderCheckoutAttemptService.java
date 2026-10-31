package com.travelgo.service;

import com.travelgo.entity.Booking;
import com.travelgo.entity.ProviderCheckoutAttempt;
import com.travelgo.enums.PaymentType;
import com.travelgo.repository.ProviderCheckoutAttemptRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderCheckoutAttemptService {
    private final ProviderCheckoutAttemptRepository repository;
    private final WorkflowRules rules;
    private final PaymentService payments;
    private final Clock clock;
    public ProviderCheckoutAttemptService(ProviderCheckoutAttemptRepository repository, WorkflowRules rules, PaymentService payments, Clock clock) {
        this.repository = repository; this.rules = rules; this.payments = payments; this.clock = clock;
    }
    // A request waiting for the booking lock must see the attempt committed by its predecessor.
    // MySQL's default REPEATABLE_READ can retain the snapshot established before that lock.
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ProviderCheckoutAttempt prepare(Booking request, PaymentType type, BigDecimal amount,
                                           LocalDateTime requestedDeadline, String mode, String deployment) {
        return prepareWithMinimum(request,type,amount,requestedDeadline,mode,deployment,1860);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ProviderCheckoutAttempt preparePayHere(Booking request,PaymentType type,BigDecimal amount,
            LocalDateTime requestedDeadline,String mode,String deployment){
        return prepareWithMinimum(request,type,amount,requestedDeadline,mode,deployment,1);
    }
    private ProviderCheckoutAttempt prepareWithMinimum(Booking request,PaymentType type,BigDecimal amount,
            LocalDateTime requestedDeadline,String mode,String deployment,long minimumRemaining){
        Booking booking = rules.lock(request.getId()); rules.owner(booking);
        if (!payments.canPay(booking, type) || amount.compareTo(payments.expectedAmount(booking, type)) != 0)
            throw new IllegalStateException("This payment quote is unavailable or has changed. Refresh checkout.");
        LocalDateTime deadline = type == PaymentType.FULL_PACKAGE ? booking.getPackagePaymentDeadline() : rules.visa(booking).getPaymentExpiresAt();
        if (deadline == null || !deadline.equals(requestedDeadline))
            throw new IllegalStateException("The payment deadline has changed. Refresh checkout.");
        long now = clock.instant().getEpochSecond();
        for (ProviderCheckoutAttempt attempt : repository.findByBooking_IdAndPaymentTypeOrderByIdDesc(booking.getId(), type)) {
            if (mode.equals(attempt.getPaymentMode()) && deployment.equals(attempt.getDeploymentReference())
                    && amount.compareTo(attempt.getAmount()) == 0 && deadline.equals(attempt.getQuoteDeadline())
                    && attempt.getExpiresAt() > now) return attempt;
        }
        long deadlineEpoch = deadline.atZone(clock.getZone()).toEpochSecond();
        if (deadlineEpoch < now + minimumRemaining) throw new IllegalStateException(minimumRemaining==1860
                ?"Online checkout needs at least 31 minutes before the payment deadline. Contact the visa officer."
                :"The payment deadline has passed. Contact the visa officer.");
        ProviderCheckoutAttempt attempt = new ProviderCheckoutAttempt();
        attempt.setBooking(booking); attempt.setPaymentType(type); attempt.setAmount(amount);
        attempt.setQuoteDeadline(deadline); attempt.setExpiresAt(Math.min(deadlineEpoch, now + 23 * 60 * 60));
        attempt.setPaymentMode(mode); attempt.setDeploymentReference(deployment); attempt.setCreatedAt(clock.instant());
        return repository.saveAndFlush(attempt);
    }
    @Transactional
    public void completed(Long id, String sessionReference, String url) {
        ProviderCheckoutAttempt attempt = repository.lock(id).orElseThrow();
        rules.owner(attempt.getBooking());
        if (attempt.getSessionReference() != null && !attempt.getSessionReference().equals(sessionReference))
            throw new IllegalStateException("The provider returned different sessions for the same checkout attempt. Staff review is required.");
        attempt.setSessionReference(sessionReference); attempt.setCheckoutUrl(url);
    }
    public void verify(Long id, Booking booking, PaymentType type, BigDecimal amount, long deadline,
                       String mode, String deployment, String sessionReference, long expiresAt) {
        ProviderCheckoutAttempt attempt = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Stripe checkout attempt."));
        if (!attempt.getBooking().getId().equals(booking.getId()) || attempt.getPaymentType() != type
                || amount.compareTo(attempt.getAmount()) != 0 || !mode.equals(attempt.getPaymentMode())
                || !deployment.equals(attempt.getDeploymentReference()) || attempt.getExpiresAt() != expiresAt
                || attempt.getQuoteDeadline().atZone(clock.getZone()).toEpochSecond() != deadline
                || (attempt.getSessionReference() != null && !attempt.getSessionReference().equals(sessionReference)))
            throw new IllegalArgumentException("Stripe session does not match its reserved checkout attempt.");
    }
}
