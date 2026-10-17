package com.travelgo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable booking-locked claim before any outbound refund, with no automatic resend. */
@Service("payHereRefunds")
public class PayHereRefundService {
    private final RefundRepository refunds;private final ProviderRefundAttemptRepository attempts;
    private final ProviderCheckoutAttemptRepository checkouts;private final PayHereService payHere;
    private final PayHereMerchantClient merchant;private final WorkflowRules rules;private final AuditService audit;
    private final Clock clock;private final TransactionTemplate tx;
    @PersistenceContext private EntityManager em;
    public PayHereRefundService(RefundRepository refunds,ProviderRefundAttemptRepository attempts,ProviderCheckoutAttemptRepository checkouts,
            PayHereService payHere,PayHereMerchantClient merchant,WorkflowRules rules,AuditService audit,Clock clock,PlatformTransactionManager manager){
        this.refunds=refunds;this.attempts=attempts;this.checkouts=checkouts;this.payHere=payHere;this.merchant=merchant;this.rules=rules;this.audit=audit;this.clock=clock;
        this.tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public boolean canUse(Refund refund){return payHere.isConfigured()&&refund!=null&&refund.getPayment()!=null&&"PayHere Checkout".equals(refund.getPayment().getPaymentMethod());}
    public Optional<ProviderRefundAttempt> attempt(Long refundId){rules.requireRole("VISA_OFFICER");return attempts.findByRefund_Id(refundId);}
    private record Claim(Long id,String paymentId,String amount,ProviderCheckoutAttempt checkout,boolean send){}
    public ProviderRefundAttempt submit(Long refundId){
        rules.requireRole("VISA_OFFICER");if(!payHere.isConfigured())throw new IllegalStateException("PayHere refund processing is unavailable.");
        Claim claim=tx.execute(s->{Refund refund=locked(refundId);var existing=attempts.findByRefund_Id(refundId);
            if(existing.isPresent())return new Claim(existing.get().getId(),null,null,null,false);
            validate(refund);var checkout=checkout(refund);ProviderRefundAttempt a=new ProviderRefundAttempt();
            a.setRefund(refund);a.setOperationReference("payhere-refund-"+UUID.randomUUID());a.setPaymentMode(payHere.getMode());a.setDeploymentReference(payHere.deploymentReference());
            a.setOrderReference(checkout.getSessionReference());a.setPaymentReference(refund.getPayment().getTransactionReference());a.setAmount(refund.getAmount());
            a.setRequestedBy(rules.actor());a.setCreatedAt(clock.instant());a.setUpdatedAt(clock.instant());attempts.saveAndFlush(a);
            audit.logAction("PAYHERE_REFUND_CLAIMED","Refund",refundId,"One provider refund send claimed; amount: "+a.getAmount()+" USD; operation: "+a.getOperationReference());
            return new Claim(a.getId(),a.getPaymentReference().substring(3),PayHereService.money(a.getAmount()),checkout,true);
        });
        if(!claim.send())return attempts.findById(claim.id()).orElseThrow();
        try{
            // Before sending, validate the original authenticated payment and ensure no existing refund/chargeback is indicated.
            var a=attempts.findById(claim.id()).orElseThrow();var original=payHere.verifiedPayment(claim.checkout(),claim.paymentId());
            if(!"RECEIVED".equals(original.path("status").asText()))throw new IllegalStateException("The provider already reports a refund or chargeback. Review the original payment.");
            JsonNode response=merchant.refund(claim.paymentId(),claim.amount(),"TravelGO refund #"+refundId+" "+a.getOperationReference());
            int status=response.path("status").asInt(Integer.MIN_VALUE);String ref=response.path("data").asText("");
            if(status!=1||!ref.matches("[0-9]{1,30}"))throw new IllegalStateException("The provider did not confirm a uniquely identified refund request.");
            tx.executeWithoutResult(s->{var current=lockedAttempt(refundId);
                if(current.getProviderRefundReference()!=null&&!current.getProviderRefundReference().equals(ref)){
                    String conflict="Late PayHere response references refund "+ref+" instead of the recorded "+current.getProviderRefundReference()+". Investigate the merchant settlement records.";
                    String priorNote=current.getReviewNote()==null?"":current.getReviewNote();
                    if(priorNote.length()+conflict.length()+1<=1000)current.setReviewNote(priorNote+" "+conflict);
                    if(!"SUCCEEDED".equals(current.getStatus()))current.setStatus("REVIEW_REQUIRED");
                    current.setUpdatedAt(clock.instant());attempts.saveAndFlush(current);
                    audit.logAction("PAYHERE_REFUND_EVIDENCE_CONFLICT","Refund",refundId,conflict+" The existing financial settlement and officer evidence were preserved.");return;
                }
                if("SUCCEEDED".equals(current.getStatus()))return;
                current.setStatus("PENDING");current.setProviderRefundReference(ref);current.setUpdatedAt(clock.instant());
                if(current.getReviewedBy()==null)current.setReviewNote("PayHere accepted the refund request. Await completed settlement evidence.");attempts.saveAndFlush(current);
                audit.logAction("PAYHERE_REFUND_REQUESTED","Refund",refundId,"Provider refund reference: "+ref+"; amount: "+current.getAmount()+" USD. Ledger remains pending.");});
        }catch(Exception failure){
            tx.executeWithoutResult(s->{var current=lockedAttempt(refundId);if(!"SUCCEEDED".equals(current.getStatus())){
                current.setStatus("REVIEW_REQUIRED");current.setUpdatedAt(clock.instant());if(current.getReviewedBy()==null)current.setReviewNote("PayHere did not provide a verifiable refund result. Inspect the merchant account before taking further action. This request will not be sent again.");
                attempts.saveAndFlush(current);audit.logAction("PAYHERE_REFUND_REVIEW_REQUIRED","Refund",refundId,"Refund send/result requires provider reconciliation; no automatic resend is permitted. Failure category: "+failure.getClass().getSimpleName()+".");}});
        }
        return attempts.findById(claim.id()).orElseThrow();
    }
    /** Reconciliation performs only a read at PayHere. A manual reference requires explicit documented evidence. */
    public ProviderRefundAttempt reconcile(Long refundId,String reference,BigDecimal verifiedAmount,String evidence,boolean verified){
        rules.requireRole("VISA_OFFICER");var initial=tx.execute(s->{var a=lockedAttempt(refundId);snapshot(a);return a;});
        if("SUCCEEDED".equals(initial.getStatus()))return initial;
        if(!verified||reference==null||!reference.matches("[0-9]{1,30}")||verifiedAmount==null||verifiedAmount.compareTo(initial.getAmount())!=0
                ||evidence==null||evidence.trim().length()<20||evidence.trim().length()>1000||evidence.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Confirm the exact refund amount and reference from PayHere settlement evidence, and record the evidence source (20–1,000 characters).");
        if(initial.getProviderRefundReference()!=null&&!reference.equals(initial.getProviderRefundReference()))throw new IllegalArgumentException("The refund reference does not match PayHere's recorded response.");
        ProviderCheckoutAttempt source=tx.execute(s->checkout(locked(refundId)));
        JsonNode provider=payHere.verifiedPayment(source,initial.getPaymentReference().substring(3));
        if(!"REFUNDED".equals(provider.path("status").asText()))throw new IllegalStateException("PayHere has not confirmed completed refund settlement. Keep this refund pending.");
        return tx.execute(s->{var a=lockedAttempt(refundId);if("SUCCEEDED".equals(a.getStatus()))return a;validate(a.getRefund());snapshot(a);
            if(a.getProviderRefundReference()!=null&&!reference.equals(a.getProviderRefundReference()))throw new IllegalStateException("The refund reference changed. Refresh the settlement page.");
            if(attempts.existsByProviderAndPaymentModeAndDeploymentReferenceAndProviderRefundReference("PAYHERE",a.getPaymentMode(),a.getDeploymentReference(),reference)
                    &&!reference.equals(a.getProviderRefundReference()))throw new IllegalStateException("This provider refund reference is already recorded against another refund.");
            Refund r=a.getRefund();r.setStatus(RefundStatus.REFUND_PROCESSED);r.setProcessedAt(rules.now());r.setSettlementReference("PH-REFUND-"+reference);r.setProcessedBy(rules.actor());refunds.saveAndFlush(r);
            if(r.getAmount().compareTo(r.getPayment().getAmount())==0)r.getPayment().setPaymentStatus(PaymentStatus.REFUNDED);
            a.setStatus("SUCCEEDED");a.setProviderRefundReference(reference);a.setReviewNote(evidence.trim());a.setReviewedBy(rules.actor());a.setUpdatedAt(clock.instant());attempts.saveAndFlush(a);
            audit.logAction("PAYHERE_REFUND_SETTLED","Refund",refundId,"PayHere completed payment refund verified; provider reference: "+reference+"; amount: "+a.getAmount()+" USD; evidence: "+evidence.trim());
            rules.notify(r.getPayment().getBooking().getUser(),"REFUND_RECORDED","Refund completed","A refund of $"+r.getAmount()+" was recorded for booking #"+r.getPayment().getBooking().getId()+". Contact the agency if you need the provider reference.","BOOKING",r.getPayment().getBooking().getId());return a;
        });
    }
    private Refund locked(Long id){Refund r=refunds.findById(id).orElseThrow(()->new IllegalArgumentException("Refund not found."));rules.lock(r.getPayment().getBooking().getId());em.refresh(r);em.refresh(r.getPayment());return r;}
    private ProviderRefundAttempt lockedAttempt(Long refundId){locked(refundId);var a=attempts.findByRefund_Id(refundId).orElseThrow();em.refresh(a);return a;}
    private void validate(Refund r){if(!canUse(r)||r.getStatus()!=RefundStatus.REFUND_PENDING||r.getPayment().getPaymentStatus()!=PaymentStatus.PAID
            ||r.getPayment().getBooking().getBookingStatus()!=BookingStatus.CANCELLED||!r.getPayment().getTransactionReference().matches("PH-[0-9]{1,30}"))
        throw new IllegalStateException("Only a pending PayHere refund on a cancelled, paid booking can be settled.");
        rules.money(r.getAmount(),false);if(r.getAmount().compareTo(r.getPayment().getAmount())>0)throw new IllegalStateException("The refund exceeds its original payment.");}
    private ProviderCheckoutAttempt checkout(Refund r){List<ProviderCheckoutAttempt> matches=checkouts.findByBooking_IdAndPaymentTypeOrderByIdDesc(r.getPayment().getBooking().getId(),r.getPayment().getPaymentType()).stream()
            .filter(a->a.getSettledPayment()!=null&&a.getSettledPayment().getId().equals(r.getPayment().getId())&&a.getDeploymentReference().equals(payHere.deploymentReference())&&a.getPaymentMode().equals(payHere.getMode())).toList();
        if(matches.size()!=1)throw new IllegalStateException("The original PayHere checkout cannot be identified uniquely. Review its provider history.");return matches.get(0);}
    private void snapshot(ProviderRefundAttempt a){if(!"PAYHERE".equals(a.getProvider())||!"USD".equals(a.getCurrency())||!payHere.getMode().equals(a.getPaymentMode())||!payHere.deploymentReference().equals(a.getDeploymentReference())
            ||a.getAmount().compareTo(a.getRefund().getAmount())!=0||!a.getPaymentReference().equals(a.getRefund().getPayment().getTransactionReference())
            ||!a.getOrderReference().equals(checkout(a.getRefund()).getSessionReference()))throw new IllegalStateException("The refund no longer matches its original provider request. Review the payment history.");}
}
