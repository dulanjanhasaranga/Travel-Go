package com.travelgo.service;

import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;

/** Explicit quote and acceptance. Recording a cancellation never claims that a gateway refund was sent. */
@Service("cancellations")
@Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class CancellationRequestService {
    private final CancellationRequestRepository requests;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final WorkflowRules rules;
    private final BookingService bookings;
    private final TravelPolicyService policies;
    private final BookingSummaryService summaries;
    private final AuditService audit;
    private final boolean simulated;
    private final ProviderCheckoutAttemptRepository checkoutAttempts;
    private final java.time.Clock clock;
    private final UserRepository users;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    public CancellationRequestService(CancellationRequestRepository requests,PaymentRepository payments,RefundRepository refunds,
            WorkflowRules rules,BookingService bookings,TravelPolicyService policies,BookingSummaryService summaries,AuditService audit,
            @Value("${travelgo.payments.simulated:true}") boolean simulated,ProviderCheckoutAttemptRepository checkoutAttempts,java.time.Clock clock,UserRepository users){this.requests=requests;this.payments=payments;this.refunds=refunds;
        this.rules=rules;this.bookings=bookings;this.policies=policies;this.summaries=summaries;this.audit=audit;this.simulated=simulated;this.checkoutAttempts=checkoutAttempts;this.clock=clock;this.users=users;}
    @Transactional(readOnly=true)
    public List<CancellationRequest> forBooking(Booking b){rules.owner(b);return requests.findByBooking_IdOrderByIdDesc(b.getId());}
    @Transactional(readOnly=true)
    public List<CancellationRequest> staffList(){rules.requireRole("TRAVEL_CONSULTANT");return requests.findAllByOrderByIdDesc();}
    @Transactional(readOnly=true)
    public List<Refund> pendingRefunds(){rules.requireRole("VISA_OFFICER");return refunds.findAll().stream().filter(r->r.getStatus()==RefundStatus.REFUND_PENDING).toList();}
    public CancellationRequest request(Long bookingId,String reason){
        Booking b=rules.lock(bookingId);rules.owner(b);openBooking(b);
        reason=text(reason,2000,"Cancellation reason");
        var existing=requests.findByBooking_IdOrderByIdDesc(bookingId).stream().filter(r->List.of("REQUESTED","QUOTED").contains(r.getStatus())).findFirst();
        if(existing.isPresent()){if(existing.get().getReason().equals(reason))return existing.get();throw new IllegalStateException("A cancellation request is already in progress. Contact your travel consultant for an update.");}
        CancellationRequest r=new CancellationRequest();r.setBooking(b);r.setReason(reason);r.setCreatedAt(rules.now());requests.saveAndFlush(r);
        audit.logAction("CANCELLATION_REQUESTED","Booking",b.getId(),"Customer requested a staff-reviewed cancellation. Booking remains open.");
        notifyConsultants(r,"CANCELLATION_REQUESTED","Cancellation request received",
            "Cancellation request #"+r.getId()+" for booking #"+b.getId()+" is ready for review. The booking remains open.");return r;
    }
    public CancellationRequest quote(Long id,Long version,Long policyId,Map<Long,BigDecimal> allocations,String response,LocalDateTime expiresAt){
        rules.requireRole("TRAVEL_CONSULTANT");CancellationRequest r=locked(id);version(r,version);openBooking(r.getBooking());
        if(!List.of("REQUESTED","QUOTED").contains(r.getStatus()))throw new IllegalStateException("This cancellation request is closed and cannot receive a quote.");
        if(summaries.forBooking(r.getBooking()).reviewRequired())throw new IllegalStateException("Review the payment and visa records before preparing a cancellation quote.");
        TravelPolicy p=policies.published(policyId);
        if(r.getBooking().getAcceptedPolicy()!=null&&!r.getBooking().getAcceptedPolicy().getId().equals(policyId))
            throw new IllegalArgumentException("Use the policy version accepted for this booking.");
        if(expiresAt==null||!expiresAt.isAfter(rules.now())||!expiresAt.isBefore(r.getBooking().getTravelDate().atStartOfDay()))
            throw new IllegalArgumentException("Choose a quote expiry after now and before the travel date.");
        var successful=successful(r.getBooking());
        if(allocations==null||!allocations.keySet().equals(successful.stream().map(Payment::getId).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("Enter a refund amount for every paid payment. Use zero where no refund applies.");
        r.getLines().clear();requests.flush();
        for(Payment payment:successful){BigDecimal amount=allocations.get(payment.getId());rules.money(amount,true);
            if(amount.compareTo(payment.getAmount())>0)throw new IllegalArgumentException("A refund cannot exceed its original payment.");
            if(refunds.findByPayment_Id(payment.getId()).isPresent())throw new IllegalStateException("Review the existing refund before quoting another refund.");
            CancellationRefundLine line=new CancellationRefundLine();line.setRequest(r);line.setPayment(payment);line.setAmount(amount);r.getLines().add(line);}
        r.setPolicy(p);r.setStaffResponse(text(response,4000,"Cancellation terms and retained charges"));r.setQuotedBy(rules.actor());r.setQuotedAt(rules.now());r.setExpiresAt(expiresAt);
        r.setPaymentFingerprint(fingerprint(successful));r.setStatus("QUOTED");requests.saveAndFlush(r);
        rules.bookingNotice(r.getBooking(),"CANCELLATION_QUOTED","Cancellation quote ready","Review the cancellation terms and refund allocation for booking #"+r.getBooking().getId()+" before accepting.");return r;
    }
    public CancellationRequest accept(Long id,Long version,boolean accepted){
        CancellationRequest r=locked(id);rules.owner(r.getBooking());
        if("ACCEPTED".equals(r.getStatus()))return r;
        version(r,version);openBooking(r.getBooking());
        if(!accepted||!"QUOTED".equals(r.getStatus()))throw new IllegalArgumentException("Review and accept the current cancellation quote.");
        if(!rules.now().isBefore(r.getExpiresAt()))throw new IllegalStateException("The cancellation quote expired. Ask your consultant for an updated quote.");
        if(!fingerprint(successful(r.getBooking())).equals(r.getPaymentFingerprint()))throw new IllegalStateException("Payment records changed. An updated cancellation quote is required.");
        for(var line:r.getLines())if(refunds.findByPayment_Id(line.getPayment().getId()).isPresent())throw new IllegalStateException("A refund is already recorded. Contact the agency to review it before continuing.");
        r.setStatus("ACCEPTED");r.setAcceptedAt(rules.now());requests.saveAndFlush(r);
        for(var line:r.getLines())if(line.getAmount().signum()>0){Refund refund=new Refund();refund.setPayment(line.getPayment());refund.setAmount(line.getAmount());
            refund.setReason("Accepted cancellation request #"+r.getId()+" · policy #"+r.getPolicy().getId());refund.setStatus(RefundStatus.REFUND_PENDING);refunds.saveAndFlush(refund);}
        bookings.cancelAcceptedRequest(r.getBooking().getId(),r.getId());
        audit.logAction("CANCELLATION_ACCEPTED","Booking",r.getBooking().getId(),"Customer accepted the cancellation quote. Refunds remain pending settlement.");return r;
    }
    public CancellationRequest close(Long id,Long version,String response,boolean withdrawn){
        CancellationRequest r=locked(id);
        if(withdrawn)rules.owner(r.getBooking());else rules.requireRole("TRAVEL_CONSULTANT");
        String target=withdrawn?"WITHDRAWN":"DECLINED";
        if(target.equals(r.getStatus()))return r;version(r,version);
        if(!List.of("REQUESTED","QUOTED").contains(r.getStatus()))throw new IllegalStateException("This cancellation request is already closed.");
        r.setStatus(target);r.setStaffResponse(text(response,4000,withdrawn?"Withdrawal reason":"Staff response"));requests.saveAndFlush(r);
        if(withdrawn)notifyConsultants(r,"CANCELLATION_WITHDRAWN","Cancellation request withdrawn",
            "The customer withdrew cancellation request #"+r.getId()+" for booking #"+r.getBooking().getId()+".");
        else rules.bookingNotice(r.getBooking(),"CANCELLATION_DECLINED","Cancellation request declined",
            "Your cancellation request for booking #"+r.getBooking().getId()+" was declined. "+r.getStaffResponse());
        return r;
    }
    private void notifyConsultants(CancellationRequest request,String type,String title,String message){
        users.findAll().stream().filter(u->u.isActive()&&rules.role(u,"TRAVEL_CONSULTANT"))
            .forEach(u->rules.notify(u,type,title,message,"BOOKING",request.getBooking().getId()));
    }
    public Refund settleSimulatedRefund(Long id,String reference){
        rules.requireRole("VISA_OFFICER");
        Refund initial=refunds.findById(id).orElseThrow(()->new IllegalArgumentException("Refund not found."));rules.lock(initial.getPayment().getBooking().getId());
        Refund r=refunds.findById(id).orElseThrow();em.refresh(r);em.refresh(r.getPayment());
        if(r.getStatus()==RefundStatus.REFUND_PROCESSED)return r;
        if(!simulated||stripePayment(r.getPayment()))throw new IllegalStateException("Verify real refunds with the payment provider. This action records simulated refunds only.");
        if(r.getStatus()!=RefundStatus.REFUND_PENDING||r.getPayment().getPaymentStatus()!=PaymentStatus.PAID||r.getPayment().getBooking().getBookingStatus()!=BookingStatus.CANCELLED)
            throw new IllegalStateException("Only a pending refund on a cancelled booking can be processed.");
        if(!canSettleSimulatedRefund(r))throw new IllegalStateException("This payment is not marked as simulated. Verify its refund through the original payment provider.");
        String evidence=text(reference,200,"Simulation reference");
        rules.money(r.getAmount(),false);if(r.getAmount().compareTo(r.getPayment().getAmount())>0)throw new IllegalStateException("The refund exceeds the original payment. Review both amounts before continuing.");
        r.setStatus(RefundStatus.REFUND_PROCESSED);r.setProcessedAt(rules.now());r.setSettlementReference("SIM-"+evidence);r.setProcessedBy(rules.actor());refunds.saveAndFlush(r);
        if(r.getAmount().compareTo(r.getPayment().getAmount())==0)r.getPayment().setPaymentStatus(PaymentStatus.REFUNDED);
        rules.notify(r.getPayment().getBooking().getUser(),"REFUND_RECORDED","Simulated refund recorded","A simulated refund of $"+r.getAmount()+" was recorded for booking #"+r.getPayment().getBooking().getId()+". No real money was transferred.","BOOKING",r.getPayment().getBooking().getId());
        audit.logAction("SIMULATED_REFUND_SETTLED","Refund",r.getId(),"Simulated settlement reference recorded.");return r;
    }
    @Transactional(readOnly=true)
    public boolean canSettleSimulatedRefund(Refund r){
        if(!simulated||r==null||r.getPayment()==null||stripePayment(r.getPayment()))return false;
        String method=r.getPayment().getPaymentMethod();
        return method!=null&&(method.trim().equalsIgnoreCase("simulation")||method.trim().equalsIgnoreCase("simulated payment"));
    }
    private boolean stripePayment(Payment p){return p.getPaymentMethod()!=null&&(p.getPaymentMethod().toLowerCase(java.util.Locale.ROOT).contains("stripe")||p.getPaymentMethod().toLowerCase(java.util.Locale.ROOT).contains("payhere"))
        ||p.getTransactionReference()!=null&&(p.getTransactionReference().startsWith("cs_")||p.getTransactionReference().startsWith("pi_")||p.getTransactionReference().startsWith("PH-"));}
    private CancellationRequest locked(Long id){CancellationRequest initial=requests.findById(id).orElseThrow(()->new IllegalArgumentException("Cancellation request not found."));rules.lock(initial.getBooking().getId());em.refresh(initial);return initial;}
    private void version(CancellationRequest r,Long version){if(!Objects.equals(r.getVersion(),version))throw new IllegalStateException("The cancellation request changed. Refresh before continuing.");}
    /** Shared advisory state for the page and the locked mutation check. Ownership is enforced separately. */
    @Transactional(readOnly=true)
    public boolean canRequestCancellation(Booking b){return b!=null&&b.getBookingStatus()!=null&&b.getTravelDate()!=null
        &&!List.of(BookingStatus.CANCELLED,BookingStatus.DECLINED,BookingStatus.EXPIRED,BookingStatus.COMPLETED).contains(b.getBookingStatus())
        &&b.getTravelDate().atStartOfDay().isAfter(rules.now());}
    private void openBooking(Booking b){if(!canRequestCancellation(b))throw new IllegalStateException("Cancellation is unavailable because this booking is closed or the departure date has passed. Contact the agency for help.");}
    private List<Payment> successful(Booking b){var all=payments.findByBooking_Id(b.getId());
        for(var type:List.of(PaymentType.VISA_DOCUMENTATION,PaymentType.FULL_PACKAGE))
            for(var attempt:checkoutAttempts.findByBooking_IdAndPaymentTypeOrderByIdDesc(b.getId(),type))
                if(attempt.getExpiresAt()>clock.instant().getEpochSecond()&&!verifiedSettlement(attempt,all))
                    throw new IllegalStateException("A hosted checkout is still open or awaiting settlement. Resolve it with the provider or wait for its expiry before cancelling.");
        if(all.stream().anyMatch(p->p.getPaymentStatus()==PaymentStatus.PENDING))throw new IllegalStateException("A payment is still pending. Review it before issuing or accepting a cancellation quote.");
        if(all.stream().anyMatch(p->p.getPaymentStatus()==PaymentStatus.REFUNDED))throw new IllegalStateException("Review the existing refunds before continuing with cancellation.");
        var result=all.stream().filter(p->p.getPaymentStatus()==PaymentStatus.PAID).sorted(Comparator.comparing(Payment::getId)).toList();
        for(var p:result){if(p.getPaidAt()==null)throw new IllegalStateException("A paid payment has no settlement date. Review its payment record before continuing.");rules.money(p.getAmount(),false);
            if(p.getPaymentType()!=PaymentType.VISA_DOCUMENTATION&&p.getPaymentType()!=PaymentType.FULL_PACKAGE)throw new IllegalStateException("Review the installment payment history before continuing with cancellation.");
            if(p.getAmount().compareTo(rules.expected(b,p.getPaymentType()))!=0)throw new IllegalStateException("The original payment does not match its issued charge. Review the payment record before continuing.");}
        if(result.stream().map(Payment::getPaymentType).distinct().count()!=result.size())throw new IllegalStateException("More than one paid payment is recorded for the same charge. Review the duplicates before continuing.");return result;}
    private boolean verifiedSettlement(ProviderCheckoutAttempt attempt,List<Payment> records){
        Payment linked=attempt.getSettledPayment();
        return linked!=null&&attempt.getSessionReference()!=null
                &&records.stream().anyMatch(p->p.getId().equals(linked.getId())&&p.getPaymentStatus()==PaymentStatus.PAID
                &&p.getPaymentType()==attempt.getPaymentType()&&p.getAmount().compareTo(attempt.getAmount())==0
                &&p.getTransactionReference()!=null&&(
                    "Stripe Checkout".equals(p.getPaymentMethod())&&attempt.getSessionReference().matches("cs_"+attempt.getPaymentMode()+"_[A-Za-z0-9]+")&&p.getTransactionReference().matches("pi_[A-Za-z0-9]+")
                    ||"PayHere Checkout".equals(p.getPaymentMethod())&&attempt.getDeploymentReference().startsWith("payhere-")&&attempt.getSessionReference().matches("TGPH-[1-9][0-9]*-[a-f0-9]{24}")&&p.getTransactionReference().matches("PH-[0-9]{1,30}")));
    }
    private String fingerprint(List<Payment> list){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(list.stream()
        .map(p->p.getId()+":"+p.getAmount().toPlainString()+":"+p.getPaymentStatus()+":"+p.getPaidAt()+":"+p.getTransactionReference()).collect(java.util.stream.Collectors.joining(";")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private String text(String value,int max,String label){if(value==null||value.trim().isEmpty()||value.trim().length()>max)throw new IllegalArgumentException(label+" is required (up to "+max+" characters).");return value.trim();}
}
