package com.travelgo.service;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class RefundService {
    private final RefundRepository repository;
    private final PaymentRepository paymentRepository;
    private final WorkflowRules rules;
    public RefundService(RefundRepository repository, PaymentRepository paymentRepository, WorkflowRules rules) { this.repository = repository; this.paymentRepository = paymentRepository; this.rules = rules; }
    public List<Refund> findAll() { return repository.findAll(); }
    public Optional<Refund> findById(Long id) { return repository.findById(id); }
    public void simulateVisaRejectionRefund(VisaApplication visa, Payment ignored) {
        rules.requireRole("VISA_OFFICER");
        Booking b = rules.lock(visa.getBooking().getId());
        VisaApplication current = rules.visa(b);
        if (current.getStatus() != VisaStatus.REJECTED || b.getBookingStatus() != BookingStatus.CANCELLED)
            throw new IllegalStateException("Refund requires a rejected visa and cancelled booking.");
        Payment payment = rules.successful(b, PaymentType.VISA_DOCUMENTATION)
            .orElseThrow(() -> new IllegalStateException("No successful upfront payment exists. Staff reconciliation required."));
        rules.money(current.getDocumentationCharge(), true);
        if (payment.getAmount().compareTo(current.getTotalCharge()) != 0)
            throw new IllegalStateException("Original payment does not match the issued charges.");
        Optional<Refund> existing = repository.findByPayment_Id(payment.getId());
        if (existing.isPresent()) {
            if (existing.get().getAmount().compareTo(current.getDocumentationCharge()) != 0 || existing.get().getStatus() != RefundStatus.REFUND_PROCESSED)
                throw new IllegalStateException("Existing refund requires staff reconciliation.");
            return;
        }
        if (payment.getPaymentStatus() != PaymentStatus.PAID) throw new IllegalStateException("Payment is already refunded without a matching refund record.");
        Refund refund = new Refund(); refund.setPayment(payment); refund.setAmount(current.getDocumentationCharge());
        refund.setReason("Visa rejected: documentation charge only. " + current.getRejectionReason());
        refund.setStatus(RefundStatus.REFUND_PROCESSED); refund.setProcessedAt(rules.now()); repository.saveAndFlush(refund);
        if (refund.getAmount().compareTo(payment.getAmount()) == 0) payment.setPaymentStatus(PaymentStatus.REFUNDED);
    }

    public void processCancellationRefunds(Booking booking) {
        List<Payment> allPayments = paymentRepository.findByBooking_Id(booking.getId());
        // Refund all successful payments that haven't been refunded yet
        for (Payment payment : allPayments) {
            if (payment.getPaymentStatus() == PaymentStatus.PAID) {
                // Check if a refund already exists
                Optional<Refund> existing = repository.findByPayment_Id(payment.getId());
                if (existing.isEmpty()) {
                    Refund refund = new Refund(); 
                    refund.setPayment(payment); 
                    refund.setAmount(payment.getAmount());
                    refund.setReason("Booking cancelled: full refund of " + payment.getPaymentType());
                    refund.setStatus(RefundStatus.REFUND_PROCESSED); 
                    refund.setProcessedAt(rules.now()); 
                    repository.saveAndFlush(refund);
                    payment.setPaymentStatus(PaymentStatus.REFUNDED);
                }
            }
        }
    }
}
