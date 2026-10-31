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
    private final SupplierReservationService supplierReservations;
    private final BookingService bookings;
    private final AuditService audit;
    private final ProviderCheckoutAttemptRepository checkoutAttempts;
    private final java.time.Clock clock;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public PaymentService(PaymentRepository repository, RefundRepository refunds, WorkflowRules rules, BookingEmailService email, SupplierReservationService supplierReservations,BookingService bookings,AuditService audit,ProviderCheckoutAttemptRepository checkoutAttempts,java.time.Clock clock) {
        this.repository = repository; this.refunds = refunds; this.rules = rules;this.email=email;
        this.supplierReservations=supplierReservations;
        this.bookings=bookings;
        this.audit=audit;
        this.checkoutAttempts=checkoutAttempts;
        this.clock=clock;
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
        if (type == PaymentType.PACKAGE_DEPOSIT || type == PaymentType.PACKAGE_BALANCE) {
            throw new IllegalStateException("Installment payments are no longer available. Use final package payment after visa approval.");
        }
        rules.eligible(b);
        VisaApplication visa = rules.visa(b);
        rules.requireVerifiedDocuments(visa);
        if (type == PaymentType.VISA_DOCUMENTATION) {
            if (visa.getStatus() != VisaStatus.PAYMENT_PENDING)
                throw new IllegalStateException("Visa charges are not ready for payment. Check the application status.");
            if (visa.getPaymentExpiresAt() == null || !rules.now().isBefore(visa.getPaymentExpiresAt()))
                throw new IllegalStateException("The visa payment deadline has passed. Ask your visa officer to reissue the charges.");
        } else if (type == PaymentType.FULL_PACKAGE) {
            if (!supplierReservations.ready(b))
                throw new IllegalStateException("Supplier confirmation is required before final payment and confirmation. Contact your consultant.");
            if (visa.getStatus() != VisaStatus.APPROVED) throw new IllegalStateException("An approved visa is required before final payment.");
            rules.requirePaid(b, PaymentType.VISA_DOCUMENTATION);
            if (b.getPackagePaymentDeadline() == null || !rules.now().isBefore(b.getPackagePaymentDeadline()))
                throw new IllegalStateException("The final payment deadline has passed. Contact your travel consultant.");
        }
    }
    private void quote(Booking b, PaymentType type, BigDecimal amount) {
        rules.money(amount, false);
        if (amount.compareTo(rules.expected(b, type)) != 0)
            throw new IllegalArgumentException("The amount does not match the current charge. Refresh the payment page before continuing.");
    }
    public Payment processPayment(Payment request) {
        Booking b = rules.lock(request.getBooking().getId()); rules.owner(b);
        return pay(b, request.getPaymentType(), request.getAmount(), request.getPaymentMethod(), request.getTransactionReference(), null);
    }
    /** Call only after StripeService has validated the signed session against its stored checkout attempt. */
    public Payment processProviderPayment(Payment request,Long attemptId,String sessionReference) {
        Booking b=rules.lock(request.getBooking().getId());rules.owner(b);
        if(!"Stripe Checkout".equals(request.getPaymentMethod())||request.getTransactionReference()==null
                ||!request.getTransactionReference().matches("pi_[A-Za-z0-9]+")||sessionReference==null
                ||!sessionReference.matches("cs_(test|live)_[A-Za-z0-9]+"))
            throw new IllegalArgumentException("Verified provider payment references are required.");
        quote(b,request.getPaymentType(),request.getAmount());
        ProviderCheckoutAttempt attempt=checkoutAttempts.lock(attemptId)
                .orElseThrow(()->new IllegalArgumentException("Unknown provider checkout attempt."));
        if(!attempt.getBooking().getId().equals(b.getId())||attempt.getPaymentType()!=request.getPaymentType()
                ||attempt.getAmount().compareTo(request.getAmount())!=0
                ||!sessionReference.startsWith("cs_"+attempt.getPaymentMode()+"_")
                ||(attempt.getSessionReference()!=null&&!attempt.getSessionReference().equals(sessionReference)))
            throw new IllegalArgumentException("Provider settlement does not match its checkout attempt.");
        Payment paid;
        var prior=rules.successful(b,request.getPaymentType());
        if(prior.isPresent()) {
            paid=prior.get();
            if(!"Stripe Checkout".equals(paid.getPaymentMethod())||!request.getTransactionReference().equals(paid.getTransactionReference())
                    ||paid.getAmount().compareTo(request.getAmount())!=0)
                throw new IllegalStateException("A different successful payment is already recorded. Staff reconciliation is required.");
        } else paid=pay(b,request.getPaymentType(),request.getAmount(),request.getPaymentMethod(),request.getTransactionReference(),null);
        if(attempt.getSettledPayment()!=null&&!attempt.getSettledPayment().getId().equals(paid.getId()))
            throw new IllegalStateException("The checkout attempt already belongs to another recorded payment. Staff reconciliation is required.");
        if(attempt.getSettledPayment()==null) {
            attempt.setSessionReference(sessionReference);attempt.setSettledPayment(paid);
            checkoutAttempts.saveAndFlush(attempt);
            audit.logAction("PROVIDER_CHECKOUT_SETTLED","Payment",paid.getId(),"Verified checkout attempt #"+attempt.getId()
                    +"; session: "+sessionReference+"; provider payment: "+paid.getTransactionReference());
        }
        return paid;
    }
    /** Only PayHereService's verified notification AND authenticated Merchant API evidence may call this. */
    public Payment processPayHerePayment(Payment request, Long attemptId, String orderReference, String mode, String deployment) {
        Booking b=rules.lock(request.getBooking().getId());rules.owner(b);
        if(!"PayHere Checkout".equals(request.getPaymentMethod())||request.getTransactionReference()==null
                ||!request.getTransactionReference().matches("PH-[0-9]{1,30}")||orderReference==null
                ||!orderReference.matches("TGPH-[1-9][0-9]*-[a-f0-9]{24}")||deployment==null||!deployment.startsWith("payhere-"))
            throw new IllegalArgumentException("Verified PayHere payment references are required.");
        quote(b,request.getPaymentType(),request.getAmount());
        ProviderCheckoutAttempt attempt=checkoutAttempts.lock(attemptId).orElseThrow(()->new IllegalArgumentException("Unknown PayHere checkout."));
        if(!attempt.getBooking().getId().equals(b.getId())||attempt.getPaymentType()!=request.getPaymentType()
                ||attempt.getAmount().compareTo(request.getAmount())!=0||!mode.equals(attempt.getPaymentMode())
                ||!deployment.equals(attempt.getDeploymentReference())||!orderReference.equals(attempt.getSessionReference()))
            throw new IllegalArgumentException("PayHere settlement does not match its original checkout.");
        Payment paid;var prior=rules.successful(b,request.getPaymentType());
        if(prior.isPresent()){
            paid=prior.get();
            if(!"PayHere Checkout".equals(paid.getPaymentMethod())||!request.getTransactionReference().equals(paid.getTransactionReference())
                    ||paid.getAmount().compareTo(request.getAmount())!=0)
                throw new IllegalStateException("A different payment already settles this charge. Staff reconciliation is required.");
        } else {
            if(clock.instant().getEpochSecond()>=attempt.getExpiresAt())
                throw new IllegalStateException("This PayHere checkout expired. Staff reconciliation is required.");
            paid=pay(b,request.getPaymentType(),request.getAmount(),request.getPaymentMethod(),request.getTransactionReference(),null);
        }
        if(attempt.getSettledPayment()!=null&&!attempt.getSettledPayment().getId().equals(paid.getId()))
            throw new IllegalStateException("The PayHere checkout already belongs to another payment. Staff reconciliation is required.");
        if(attempt.getSettledPayment()==null){attempt.setSettledPayment(paid);checkoutAttempts.saveAndFlush(attempt);
            audit.logAction("PAYHERE_CHECKOUT_SETTLED","Payment",paid.getId(),"Verified PayHere order: "+orderReference+"; payment: "+paid.getTransactionReference());}
        return paid;
    }
    public Payment recordPaymentFailure(Booking b, PaymentType type, String method, String reference) {
        b = rules.lock(b.getId()); rules.owner(b);
        validateStage(b, type);
        if (rules.successful(b, type).isPresent()) throw new IllegalStateException("This charge is already paid.");
        Payment p = new Payment(); p.setBooking(b); p.setPaymentType(type); p.setAmount(rules.expected(b, type));
        p.setPaymentMethod(method); p.setPaymentStatus(PaymentStatus.FAILED);
        p.setTransactionReference(reference == null || reference.isBlank() ? "FAIL-" + UUID.randomUUID() : reference.trim());
        p.setExpiresAt(type == PaymentType.VISA_DOCUMENTATION ? rules.visa(b).getPaymentExpiresAt() : b.getPackagePaymentDeadline());
        repository.save(p);
        rules.notify(b.getUser(), "PAYMENT_FAILED", "Payment attempt failed", "The payment attempt for booking #" + b.getId() + " failed. Check your payment page before retrying.", "PAYMENT", p.getId());
        return p;
    }
    private Payment pay(Booking b, PaymentType type, BigDecimal amount, String method, String reference, Payment pending) {
        if (type == null) throw new IllegalArgumentException("Choose a payment type.");
        quote(b, type, amount);
        Optional<Payment> existing = rules.successful(b, type);
        if (existing.isPresent()) {
            Payment previous = existing.get();
            if (previous.getAmount().compareTo(amount) != 0) throw new IllegalStateException("The recorded payment does not match the charge. Ask the agency to review it.");
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
        } else if (type == PaymentType.FULL_PACKAGE) {
            bookings.recordPaidConfirmation(b.getId()); email.payment(p);
            rules.bookingNotice(b, "BOOKING_CONFIRMED", "Booking confirmed", "Booking #" + b.getId() + " is confirmed. View your trip details."); 
        }
        return p;
    }
    public Payment recordManualPayment(Long bookingId, PaymentType type, BigDecimal amount, String method,
            String reference, PaymentStatus status, LocalDateTime paidAt, String remarks) {
        rules.requireRole("VISA_OFFICER"); Booking b = rules.lock(bookingId);
        if(type==null)throw new IllegalArgumentException("Choose a payment type.");
        method=manualText(method,100,"Payment method",true);
        reference=manualText(reference,200,"Settlement reference",!explicitSimulation(method));
        remarks=manualText(remarks,1000,"Audit remarks",false);
        if(providerMethod(method)||reference!=null&&(reference.startsWith("cs_")||reference.startsWith("pi_")||reference.startsWith("PH-")))
            throw new IllegalArgumentException("Record provider payments through their verified webhook. Choose the actual offline payment method.");
        if (status == PaymentStatus.PAID) {
            var previous=rules.successful(b,type);
            Payment p=pay(b,type,amount,method,reference,null);
            auditManual(p,previous.isPresent()?"MANUAL_PAYMENT_EVIDENCE_RECORDED":"MANUAL_PAYMENT_RECORDED",
                    previous.map(Payment::getPaymentStatus).orElse(null),reference,remarks);
            return p;
        }
        if (status != PaymentStatus.PENDING && status != PaymentStatus.FAILED)
            throw new IllegalArgumentException("Choose Pending, Paid or Failed for a new manual payment record.");
        quote(b, type, amount); validateStage(b, type);
        if (rules.successful(b, type).isPresent()) throw new IllegalStateException("This charge is already paid.");
        Payment p = new Payment(); p.setBooking(b); p.setPaymentType(type); p.setAmount(rules.expected(b, type));
        p.setPaymentMethod(method); p.setPaymentStatus(status);
        p.setTransactionReference(reference == null || reference.isBlank() ? "OFFLINE-" + UUID.randomUUID() : reference.trim());
        p.setExpiresAt(type == PaymentType.VISA_DOCUMENTATION ? rules.visa(b).getPaymentExpiresAt() : b.getPackagePaymentDeadline());
        repository.saveAndFlush(p);
        auditManual(p,"MANUAL_PAYMENT_RECORDED",null,reference,remarks);
        if (status == PaymentStatus.FAILED) rules.notify(b.getUser(), "PAYMENT_FAILED", "Payment attempt failed", "The payment attempt for booking #" + b.getId() + " failed. Check your payment page before retrying.", "PAYMENT", p.getId());
        return p;
    }
    public Payment updatePaymentStatus(Long id, PaymentStatus status, String reference, String remarks) {
        rules.requireRole("VISA_OFFICER");
        reference=manualText(reference,200,"Settlement reference",false);
        remarks=manualText(remarks,1000,"Audit remarks",false);
        Payment original = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Payment not found."));
        Booking b = rules.lock(original.getBooking().getId());
        Payment p = repository.findById(id).orElseThrow(); em.refresh(p);
        if (status == PaymentStatus.REFUNDED) throw new IllegalStateException("Use the visa rejection or accepted cancellation refund workflow to record a refund.");
        if (p.getPaymentStatus() == status) {
            if(reference!=null||remarks!=null)auditManual(p,"MANUAL_PAYMENT_EVIDENCE_RECORDED",status,reference,remarks);
            return p;
        }
        if (p.getPaymentStatus() != PaymentStatus.PENDING) throw new IllegalStateException("Only pending payments can change status. Start a new payment attempt after a failure.");
        if (status == PaymentStatus.PAID) {
            if(rules.successful(b,p.getPaymentType()).filter(settled->!settled.getId().equals(p.getId())).isPresent())
                throw new IllegalStateException("A different payment already settles this charge. Review the pending payment before continuing.");
            manualText(p.getPaymentMethod(),100,"Payment method",true);
            if(!explicitSimulation(p.getPaymentMethod()))reference=manualText(reference,200,"Verified settlement reference",true);
            if(providerMethod(p.getPaymentMethod())||reference!=null&&(reference.startsWith("cs_")||reference.startsWith("pi_")||reference.startsWith("PH-")))
                throw new IllegalArgumentException("Record provider payments through their verified webhook.");
            Payment paid=pay(b,p.getPaymentType(),p.getAmount(),p.getPaymentMethod(),reference,p);
            auditManual(paid,"MANUAL_PAYMENT_STATUS_CHANGED",PaymentStatus.PENDING,reference,remarks);
            return paid;
        }
        if (status != PaymentStatus.FAILED && status != PaymentStatus.EXPIRED) throw new IllegalArgumentException("This payment status change is not allowed.");
        if (status == PaymentStatus.EXPIRED && (p.getExpiresAt() == null || rules.now().isBefore(p.getExpiresAt())))
            throw new IllegalStateException("The payment deadline has not passed. It cannot be marked expired.");
        p.setPaymentStatus(status);
        auditManual(p,"MANUAL_PAYMENT_STATUS_CHANGED",PaymentStatus.PENDING,reference,remarks);
        if (status == PaymentStatus.FAILED) rules.notify(b.getUser(), "PAYMENT_FAILED", "Payment attempt failed", "The payment attempt for booking #" + b.getId() + " failed. Check your payment page before retrying.", "PAYMENT", p.getId());
        return repository.save(p);
    }
    private String manualText(String value,int max,String label,boolean required) {
        String cleaned=value==null?null:value.trim();
        if(cleaned==null||cleaned.isEmpty()) {
            if(required)throw new IllegalArgumentException(label+" is required.");
            return null;
        }
        if(label.equals("Audit remarks"))cleaned=cleaned.replaceAll("[\\r\\n\\t]+"," ");
        if(cleaned.length()>max||cleaned.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label+" must be at most "+max+" characters.");
        return cleaned;
    }
    private boolean explicitSimulation(String method) {
        return method!=null&&(method.trim().equalsIgnoreCase("simulation")||method.trim().equalsIgnoreCase("simulated payment"));
    }
    private boolean providerMethod(String method){return method!=null&&(method.toLowerCase(java.util.Locale.ROOT).contains("stripe")||method.toLowerCase(java.util.Locale.ROOT).contains("payhere"));}
    private void auditManual(Payment p,String action,PaymentStatus previous,String submittedReference,String remarks) {
        audit.logAction(action,"Payment",p.getId(),"Status: "+(previous==null?"new":previous.name())+" -> "+p.getPaymentStatus()
                +"; ledger reference: "+p.getTransactionReference()+"; submitted settlement reference: "+(submittedReference==null?"not supplied":submittedReference)
                +"; amount: "+p.getAmount()+" USD; remarks: "+(remarks==null?"not supplied":remarks));
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
