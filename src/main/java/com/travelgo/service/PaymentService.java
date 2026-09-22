package com.travelgo.service;

import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class PaymentService {
    private final PaymentRepository repository;
    private final RefundRepository refunds;
    private final WorkflowRules rules;
    private final BookingEmailService email;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public PaymentService(PaymentRepository repository, RefundRepository refunds, WorkflowRules rules, BookingEmailService email) {
        this.repository = repository; this.refunds = refunds; this.rules = rules;this.email=email;
    }
    public List<Payment> findAll() { return repository.findAllByOrderByCreatedAtDesc(); }
    public Optional<Payment> findById(Long id) { return repository.findById(id); }
    public List<Payment> findByBookingId(Long id) { return repository.findByBooking_IdOrderByCreatedAtDesc(id); }
    public List<Payment> findByType(PaymentType type) { return repository.findByPaymentTypeOrderByCreatedAtDesc(type); }
    public List<Payment> findByStatus(PaymentStatus status) { return repository.findByPaymentStatusOrderByCreatedAtDesc(status); }
    public List<Payment> getRecentPayments(int limit) { return findAll().stream().limit(limit).toList(); }
    public boolean hasPaidVisaAndDocumentation(Long id) {
        return repository.findFirstByBooking_IdAndPaymentTypeAndPaymentStatus(id, PaymentType.VISA_DOCUMENTATION, PaymentStatus.PAID).isPresent();
    }
    public BigDecimal expectedAmount(Booking b, PaymentType type) { return rules.expected(b, type); }
    public boolean canPay(Booking b, PaymentType type) {
        try { if (rules.successful(b, type).isPresent()) return false; validateStage(b, type); return true; }
        catch (IllegalArgumentException | IllegalStateException e) { return false; }
    }
    private void validateStage(Booking b, PaymentType type) {
        rules.eligible(b);
        if (type == PaymentType.PACKAGE_DEPOSIT) {
            return;
        }
        VisaApplication visa = rules.visa(b);
        rules.requireVerifiedDocuments(visa);
        if (type == PaymentType.VISA_DOCUMENTATION) {
            if (visa.getStatus() != VisaStatus.PAYMENT_PENDING)
                throw new IllegalStateException("Visa charges are not awaiting payment.");
            if (visa.getPaymentExpiresAt() == null || !rules.now().isBefore(visa.getPaymentExpiresAt()))
                throw new IllegalStateException("Visa payment has expired. Ask the visa officer to reissue the charges.");
        } else if (type == PaymentType.PACKAGE_BALANCE || type == PaymentType.FULL_PACKAGE) {
            if (visa.getStatus() != VisaStatus.APPROVED) throw new IllegalStateException("An approved visa is required before final payment.");
            rules.requirePaid(b, PaymentType.VISA_DOCUMENTATION);
            if (type == PaymentType.PACKAGE_BALANCE) {
                rules.requirePaid(b, PaymentType.PACKAGE_DEPOSIT);
            }
            if (b.getPackagePaymentDeadline() == null || !rules.now().isBefore(b.getPackagePaymentDeadline()))
                throw new IllegalStateException("The package payment deadline has passed.");
        }
    }
    private void quote(Booking b, PaymentType type, BigDecimal amount) {
        rules.money(amount, false);
        if (amount.compareTo(rules.expected(b, type)) != 0)
            throw new IllegalArgumentException("The quoted amount has changed or is incorrect. Refresh checkout.");
    }
    public Payment processPayment(Payment request) {
        Booking b = rules.lock(request.getBooking().getId()); rules.owner(b);
        return pay(b, request.getPaymentType(), request.getAmount(), request.getPaymentMethod(), null, null);
    }
    private Payment pay(Booking b, PaymentType type, BigDecimal amount, String method, String reference, Payment pending) {
        if (type == null) throw new IllegalArgumentException("Payment type is required.");
        quote(b, type, amount);
        Optional<Payment> existing = rules.successful(b, type);
        if (existing.isPresent()) {
            Payment previous = existing.get();
            if (previous.getAmount().compareTo(amount) != 0) throw new IllegalStateException("Existing payment needs staff reconciliation.");
            if (previous.getPaymentStatus() != PaymentStatus.PAID || b.getBookingStatus() == BookingStatus.CANCELLED || b.getBookingStatus() == BookingStatus.EXPIRED)
                throw new IllegalStateException("This payment belongs to a closed booking.");
            return previous;
        }
        validateStage(b, type);
        Payment p = pending == null ? new Payment() : pending;
        p.setBooking(b); p.setPaymentType(type); p.setAmount(rules.expected(b, type));
        p.setPaymentMethod(method == null || method.isBlank() ? "Simulated payment" : method);
        if (p.getTransactionReference() == null) p.setTransactionReference(reference == null || reference.isBlank() ? "TXN-" + UUID.randomUUID() : reference.trim());
        p.setExpiresAt(type == PaymentType.VISA_DOCUMENTATION ? rules.visa(b).getPaymentExpiresAt() : b.getPackagePaymentDeadline());
        p.setPaymentStatus(PaymentStatus.PAID); p.setPaidAt(rules.now()); repository.saveAndFlush(p);
        rules.notify(b.getUser(), "PAYMENT_SUCCESS", "Payment recorded", "Payment of $" + p.getAmount() + " was recorded for booking #" + b.getId() + ".", "PAYMENT", p.getId());
        if (type == PaymentType.VISA_DOCUMENTATION) {
            rules.transition(rules.visa(b), VisaStatus.PROCESSING, "Upfront payment received. Visa processing has started.");
        } else if (type == PaymentType.PACKAGE_BALANCE || type == PaymentType.FULL_PACKAGE) { 
            b.setBookingStatus(BookingStatus.CONFIRMED); email.payment(p);
            rules.bookingNotice(b, "BOOKING_CONFIRMED", "Booking confirmed", "Booking #" + b.getId() + " is confirmed. View your trip details."); 
        }
        return p;
    }
    public Payment recordManualPayment(Long bookingId, PaymentType type, BigDecimal amount, String method,
            String reference, PaymentStatus status, LocalDateTime paidAt, String remarks) {
        rules.requireRole("VISA_OFFICER"); Booking b = rules.lock(bookingId);
        if (status == PaymentStatus.PAID) return pay(b, type, amount, method, reference, null);
        if (status != PaymentStatus.PENDING && status != PaymentStatus.FAILED)
            throw new IllegalArgumentException("New manual records must be pending, paid, or failed.");
        quote(b, type, amount); validateStage(b, type);
        if (rules.successful(b, type).isPresent()) throw new IllegalStateException("This charge is already paid.");
        Payment p = new Payment(); p.setBooking(b); p.setPaymentType(type); p.setAmount(rules.expected(b, type));
        p.setPaymentMethod(method); p.setPaymentStatus(status);
        p.setTransactionReference(reference == null || reference.isBlank() ? "OFFLINE-" + UUID.randomUUID() : reference.trim());
        p.setExpiresAt(type == PaymentType.VISA_DOCUMENTATION ? rules.visa(b).getPaymentExpiresAt() : b.getPackagePaymentDeadline());
        repository.save(p);
        if (status == PaymentStatus.FAILED) rules.notify(b.getUser(), "PAYMENT_FAILED", "Payment attempt failed", "The payment attempt for booking #" + b.getId() + " failed. Check your payment page before retrying.", "PAYMENT", p.getId());
        return p;
    }
    public Payment updatePaymentStatus(Long id, PaymentStatus status, String reference, String remarks) {
        rules.requireRole("VISA_OFFICER");
        Payment original = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Payment not found."));
        Booking b = rules.lock(original.getBooking().getId());
        Payment p = repository.findById(id).orElseThrow(); em.refresh(p);
        if (status == PaymentStatus.REFUNDED) throw new IllegalStateException("Refunds are issued only through visa rejection.");
        if (p.getPaymentStatus() == status) return p;
        if (p.getPaymentStatus() != PaymentStatus.PENDING) throw new IllegalStateException("Only pending payments can change status. Create a new attempt to retry a failure.");
        if (status == PaymentStatus.PAID) return pay(b, p.getPaymentType(), p.getAmount(), p.getPaymentMethod(), reference, p);
        if (status != PaymentStatus.FAILED && status != PaymentStatus.EXPIRED) throw new IllegalArgumentException("Invalid payment transition.");
        if (status == PaymentStatus.EXPIRED && (p.getExpiresAt() == null || rules.now().isBefore(p.getExpiresAt())))
            throw new IllegalStateException("This payment has not expired.");
        p.setPaymentStatus(status);
        if (status == PaymentStatus.FAILED) rules.notify(b.getUser(), "PAYMENT_FAILED", "Payment attempt failed", "The payment attempt for booking #" + b.getId() + " failed. Check your payment page before retrying.", "PAYMENT", p.getId());
        return repository.save(p);
    }
    private BigDecimal revenue(PaymentType type) {
        return repository.findAll().stream().filter(p -> type == null || p.getPaymentType() == type)
            .filter(p -> p.getPaymentStatus() == PaymentStatus.PAID || p.getPaymentStatus() == PaymentStatus.REFUNDED)
            .map(p -> p.getAmount().subtract(refunds.findByPayment_Id(p.getId())
                .filter(r -> r.getStatus() == RefundStatus.REFUND_PROCESSED).map(Refund::getAmount).orElse(BigDecimal.ZERO)))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    public BigDecimal getTotalRevenue() { return revenue(null); }
    public BigDecimal getVisaPaymentsTotal() { return revenue(PaymentType.VISA_DOCUMENTATION); }
    public BigDecimal getPackagePaymentsTotal() { return revenue(PaymentType.FULL_PACKAGE).add(revenue(PaymentType.PACKAGE_BALANCE)).add(revenue(PaymentType.PACKAGE_DEPOSIT)); }
    public BigDecimal getPendingReceivables() {
        return repository.findAll().stream().filter(p -> p.getPaymentStatus() == PaymentStatus.PENDING)
            .filter(p -> canPay(p.getBooking(), p.getPaymentType()))
            .collect(java.util.stream.Collectors.toMap(p -> p.getBooking().getId() + ":" + p.getPaymentType(),
                p -> rules.expected(p.getBooking(), p.getPaymentType()), (a,b) -> a))
            .values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
