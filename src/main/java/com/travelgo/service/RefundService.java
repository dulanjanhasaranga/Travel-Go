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
    private final WorkflowRules rules;
    @org.springframework.beans.factory.annotation.Value("${travelgo.payments.simulated:true}") private boolean simulated=true;
    public RefundService(RefundRepository repository, WorkflowRules rules) { this.repository = repository; this.rules = rules; }
    public List<Refund> findAll() { return repository.findAll(); }
    public Optional<Refund> findById(Long id) { return repository.findById(id); }
    public void simulateVisaRejectionRefund(VisaApplication visa, Payment ignored) {
        rules.requireRole("VISA_OFFICER");
        Booking b = rules.lock(visa.getBooking().getId());
        VisaApplication current = rules.visa(b);
        if (current.getStatus() != VisaStatus.REJECTED || b.getBookingStatus() != BookingStatus.CANCELLED)
            throw new IllegalStateException("A visa rejection refund requires a rejected application and a cancelled booking.");
        Payment payment = rules.successful(b, PaymentType.VISA_DOCUMENTATION)
            .orElseThrow(() -> new IllegalStateException("No paid visa and documentation charge is recorded. Review the payment history before refunding."));
        rules.money(current.getDocumentationCharge(), true);
        if (payment.getAmount().compareTo(current.getTotalCharge()) != 0)
            throw new IllegalStateException("The original payment does not match the issued charges. Review the payment history.");
        Optional<Refund> existing = repository.findByPayment_Id(payment.getId());
        boolean providerPayment=payment.getPaymentMethod()!=null&&payment.getPaymentMethod().toLowerCase(java.util.Locale.ROOT).contains("stripe")
            ||payment.getTransactionReference()!=null&&(payment.getTransactionReference().startsWith("cs_")||payment.getTransactionReference().startsWith("pi_"));
        String paymentMethod=payment.getPaymentMethod()==null?"":payment.getPaymentMethod().trim().toLowerCase(java.util.Locale.ROOT);
        boolean simulatedPayment=paymentMethod.equals("simulation")||paymentMethod.equals("simulated payment");
        boolean pendingSettlement=current.getDocumentationCharge().signum()>0&&(providerPayment||!simulated||!simulatedPayment);
        if (existing.isPresent()) {
            if (existing.get().getAmount().compareTo(current.getDocumentationCharge()) != 0 || (existing.get().getStatus() != RefundStatus.REFUND_PROCESSED&&!(pendingSettlement&&existing.get().getStatus()==RefundStatus.REFUND_PENDING)))
                throw new IllegalStateException("Review the existing refund before recording another refund.");
            return;
        }
        if (payment.getPaymentStatus() != PaymentStatus.PAID) throw new IllegalStateException("This payment is marked refunded, but no matching refund record was found. Review its history.");
        Refund refund = new Refund(); refund.setPayment(payment); refund.setAmount(current.getDocumentationCharge());
        refund.setReason("Visa rejected: documentation charge only. " + current.getRejectionReason());
        refund.setStatus(pendingSettlement?RefundStatus.REFUND_PENDING:RefundStatus.REFUND_PROCESSED); if(!pendingSettlement)refund.setProcessedAt(rules.now()); repository.saveAndFlush(refund);
        if (!pendingSettlement&&refund.getAmount().compareTo(payment.getAmount()) == 0) payment.setPaymentStatus(PaymentStatus.REFUNDED);
        if (refund.getAmount().signum()>0) {
            if (pendingSettlement) {
                rules.bookingNotice(b,"PAYMENT_REFUND_PENDING","Documentation refund pending",
                    "The documentation charge of $"+refund.getAmount()+" is pending refund for booking #"+b.getId()+". Check your payment details for updates.");
            } else {
                rules.bookingNotice(b,"PAYMENT_REFUND_RECORDED","Simulated documentation refund recorded",
                    "A simulated refund of the $"+refund.getAmount()+" documentation charge was recorded for booking #"+b.getId()+". No real money was transferred.");
            }
        }
    }

}
