package com.travelgo.service;

import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.travelgo.entity.Booking;
import com.travelgo.entity.ProviderPaymentReview;
import com.travelgo.repository.ProviderPaymentReviewRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProviderPaymentReviewService {
    private final ProviderPaymentReviewRepository repository;
    private final WorkflowRules rules;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public ProviderPaymentReviewService(ProviderPaymentReviewRepository repository, WorkflowRules rules, Clock clock,
                                        PlatformTransactionManager manager) {
        this.repository = repository; this.rules = rules; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    /** Only a verified webhook may call this. Never store the raw payload or credentials. */
    public void record(Event event, Session session, Booking booking, String reason) {
        if (event == null || event.getId() == null || !event.getId().matches("evt_[A-Za-z0-9]{1,95}")) return;
        try {
            transaction.executeWithoutResult(status -> {
                if (repository.existsByEventReference(event.getId())) return;
                ProviderPaymentReview review = new ProviderPaymentReview();
                review.setEventReference(event.getId()); review.setBooking(booking);
                review.setPaymentMode(Boolean.TRUE.equals(event.getLivemode()) ? "live" : "test");
                review.setReason(reason.length() <= 400 ? reason : reason.substring(0, 400));
                review.setCreatedAt(clock.instant());
                if (session != null) {
                    review.setSessionReference(reference(session.getId())); review.setPaymentReference(reference(session.getPaymentIntent()));
                    review.setCurrency(session.getCurrency() != null && session.getCurrency().matches("[a-zA-Z]{3}") ? session.getCurrency() : null);
                    if (session.getAmountTotal() != null && session.getAmountTotal() >= 0)
                        review.setAmount(BigDecimal.valueOf(session.getAmountTotal(), 2));
                }
                repository.saveAndFlush(review);
            });
        } catch (DataIntegrityViolationException duplicate) {
            if (!repository.existsByEventReference(event.getId())) throw duplicate;
        }
    }
    public List<ProviderPaymentReview> findOpen() { rules.requireRole("ADMIN"); return repository.findByStatusOrderByCreatedAtDesc("OPEN"); }
    /** Only call after verifying the notification checksum. Do not persist customer/card payloads. */
    public void recordPayHere(PayHereService.Notice notice, Booking booking, String mode, String reason) {
        String reference="ph_"+PayHereService.sha256(notice.orderId()+":"+notice.paymentId()+":"+notice.status()+":"+notice.signature());
        try {transaction.executeWithoutResult(status->{
            if(repository.existsByEventReference(reference))return;
            var review=new ProviderPaymentReview();review.setEventReference(reference);review.setBooking(booking);
            review.setSessionReference(notice.orderId());review.setPaymentReference("PH-"+notice.paymentId());
            review.setAmount(notice.amount());review.setCurrency(notice.currency());review.setPaymentMode(mode);
            review.setReason(reason.substring(0,Math.min(reason.length(),400)));review.setCreatedAt(clock.instant());repository.saveAndFlush(review);
        });}catch(DataIntegrityViolationException duplicate){if(!repository.existsByEventReference(reference))throw duplicate;}
    }
    public long countOpen() { rules.requireRole("ADMIN"); return repository.countByStatus("OPEN"); }
    public Optional<ProviderPaymentReview> findById(Long id) { rules.requireRole("ADMIN"); return repository.findById(id); }
    @Transactional
    public ProviderPaymentReview acknowledge(Long id, String note) {
        rules.requireRole("ADMIN");
        if (note == null || note.isBlank() || note.trim().length() > 1000)
            throw new IllegalArgumentException("Enter the provider investigation outcome, up to 1,000 characters.");
        ProviderPaymentReview review = repository.lock(id).orElseThrow(() -> new IllegalArgumentException("Provider payment review not found."));
        if (!"OPEN".equals(review.getStatus())) return review;
        review.setStatus("RESOLVED"); review.setReviewNote(note.trim());
        review.setReviewedBy(rules.actor()); review.setReviewedAt(clock.instant());
        return review;
    }
    private String reference(String value) { return value != null && value.matches("[A-Za-z0-9_]{1,100}") ? value : null; }
}
