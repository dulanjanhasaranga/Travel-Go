package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** H2-only synthetic evidence; no provider connection or working database access. */
class FinancialSettlementSafetyTests extends WorkflowIntegrationTests {
    @Autowired ProviderCheckoutAttemptService checkout;
    @Autowired ProviderCheckoutAttemptRepository attempts;
    @Autowired CancellationRequestService cancellations;
    @Autowired CancellationRequestRepository cancellationRepo;
    @Autowired TravelPolicyService policies;
    @Autowired AuditService audit;
    @Autowired WorkflowRules rules;
    @Autowired TravelPolicyRepository policyRepo;
    @Autowired PermissionRepository permissionRepo;
    @BeforeEach void isolatePublishedPolicies(){
        clearPublishedPolicies();
        tx.executeWithoutResult(s->{
            var role=roleRepo.findByRoleName("ADMIN").orElseThrow();var assigned=new java.util.HashSet<>(role.getPermissions());
            for(String name:List.of("SYSTEM_SETTINGS_VIEW","SYSTEM_SETTINGS_MANAGE"))
                assigned.add(permissionRepo.findByPermissionName(name).orElseGet(()->permissionRepo.save(new Permission(name,"Synthetic policy test permission","SYSTEM"))));
            role.setPermissions(assigned);roleRepo.save(role);
        });
    }
    @AfterEach void removeSyntheticPublishedPolicy(){clearPublishedPolicies();}
    private void clearPublishedPolicies(){tx.executeWithoutResult(s->policyRepo.findAll().forEach(p->p.setPublished(false)));}

    private ProviderCheckoutAttempt prepared(Booking b,VisaApplication v) {
        login(customer);
        return checkout.prepare(b,PaymentType.VISA_DOCUMENTATION,v.getTotalCharge(),v.getPaymentExpiresAt(),"test","synthetic-settlement-test");
    }
    private Payment providerRequest(Booking b,String reference) {
        Payment p=new Payment();p.setBooking(b);p.setPaymentType(PaymentType.VISA_DOCUMENTATION);
        p.setAmount(new BigDecimal("40"));p.setPaymentMethod("Stripe Checkout");p.setTransactionReference(reference);return p;
    }
    private List<AuditLog> paymentAudit(Long id) {
        return audit.getAllLogs().stream().filter(l->"Payment".equals(l.getEntityName())&&id.equals(l.getEntityId())).toList();
    }
    private CancellationRequest quotedCancellation(Booking b,Payment p) {
        login(customer);var r=cancellations.request(b.getId(),"Synthetic cancellation after checkout payment.");
        login(admin);
        String text="Synthetic local acceptance policy, never real agency approval. ".repeat(4);
        var draft=policies.draft("Synthetic payment acceptance policy",text,text,text);
        var published=policies.publish(draft.getId(),policies.current().map(TravelPolicy::getId).orElse(null),true);
        login(consultant);
        return cancellations.quote(r.getId(),r.getVersion(),published.getId(),Map.of(p.getId(),BigDecimal.TEN),
                "Synthetic $10 refund quote for the isolated acceptance test.",rules.now().plusHours(1));
    }

    @Test void bankAndCashRejectionRefundsRemainPendingEvenWithGlobalSimulation() throws Exception {
        for(String method:List.of("Bank Transfer","Cash Deposit","Not simulated")) {
            Booking b=create(false);var v=quote(b);login(officer);
            Payment p=payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),method,
                    "SYNTHETIC-RECEIPT-"+b.getId(),PaymentStatus.PAID,rules.now(),"Synthetic recorded payment only.");
            BigDecimal before=payments.getTotalRevenue();visas.rejectVisa(v.getId(),"Synthetic rejection",null);
            Refund r=refundRepo.findByPayment_Id(p.getId()).orElseThrow();
            assertEquals(RefundStatus.REFUND_PENDING,r.getStatus());assertNull(r.getProcessedAt());assertNull(r.getSettlementReference());
            assertEquals(PaymentStatus.PAID,paymentRepo.findById(p.getId()).orElseThrow().getPaymentStatus());
            assertEquals(0,before.compareTo(payments.getTotalRevenue()));
            boolean canSettle=Boolean.TRUE.equals(tx.execute(s->cancellations.canSettleSimulatedRefund(refundRepo.findById(r.getId()).orElseThrow())));
            assertFalse(canSettle);
            assertThrows(IllegalStateException.class,()->cancellations.settleSimulatedRefund(r.getId(),"ARBITRARY-NOTE"));
        }
    }
    @Test void explicitlySimulatedRejectionStillRecordsOnlyTheDocumentationRefund() throws Exception {
        Booking b=create(false);var v=processing(b);login(officer);visas.rejectVisa(v.getId(),"Synthetic rejection",null);
        var p=paymentRepo.findByBooking_Id(b.getId()).get(0);var r=refundRepo.findByPayment_Id(p.getId()).orElseThrow();
        assertEquals(RefundStatus.REFUND_PROCESSED,r.getStatus());assertEquals(0,BigDecimal.TEN.compareTo(r.getAmount()));
        assertEquals(0,new BigDecimal("40").compareTo(p.getAmount()));
    }
    @Test void manualSettlementRetainsLedgerReferenceAndAuditsSubmittedEvidenceWithActor() throws Exception {
        Booking b=create(false);quote(b);login(officer);
        var p=payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),"Bank Transfer",
                "SYNTHETIC-PENDING-"+b.getId(),PaymentStatus.PENDING,null,"Synthetic receipt is awaiting bank verification.");
        String original=p.getTransactionReference();
        payments.updatePaymentStatus(p.getId(),PaymentStatus.PAID,"SYNTHETIC-BANK-SETTLED-"+b.getId(),"Synthetic bank acceptance evidence reviewed.");
        var settled=paymentRepo.findById(p.getId()).orElseThrow();assertEquals(original,settled.getTransactionReference());
        assertEquals(0,new BigDecimal("40").compareTo(settled.getAmount()));
        var log=paymentAudit(p.getId()).stream().filter(l->"MANUAL_PAYMENT_STATUS_CHANGED".equals(l.getAction())).findFirst().orElseThrow();
        assertEquals(officer.getEmail(),log.getUsername());assertTrue(log.getDetails().contains(original));
        assertTrue(log.getDetails().contains("SYNTHETIC-BANK-SETTLED-"+b.getId()));assertTrue(log.getDetails().contains("Synthetic bank acceptance evidence reviewed."));
        long count=paymentAudit(p.getId()).size();
        payments.updatePaymentStatus(p.getId(),PaymentStatus.PAID,"SYNTHETIC-SUPPLEMENTAL-EVIDENCE","Additional synthetic review note.");
        assertEquals(count+1,paymentAudit(p.getId()).size());assertEquals(original,paymentRepo.findById(p.getId()).orElseThrow().getTransactionReference());
    }
    @Test void realManualPaymentsRequireMethodAndSourceReferenceAndRejectProviderImpersonation() throws Exception {
        Booking b=create(false);quote(b);login(officer);long before=paymentRepo.count();
        assertThrows(IllegalArgumentException.class,()->payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),null,"TEST",PaymentStatus.PAID,null,null));
        assertThrows(IllegalArgumentException.class,()->payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),"Bank Transfer",null,PaymentStatus.PAID,null,null));
        assertThrows(IllegalArgumentException.class,()->payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),"Stripe Checkout","pi_Fictional",PaymentStatus.PAID,null,null));
        assertThrows(IllegalArgumentException.class,()->payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),"Bank Transfer","x".repeat(201),PaymentStatus.PAID,null,null));
        assertThrows(IllegalArgumentException.class,()->payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),"Bank Transfer","SYNTHETIC",PaymentStatus.PAID,null,"x".repeat(1001)));
        assertThrows(IllegalArgumentException.class,()->payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),"Bank Transfer","SYNTHETIC\u0000REFERENCE",PaymentStatus.PAID,null,null));
        assertEquals(before,paymentRepo.count());
    }
    @Test void pendingRealPaymentCannotClaimSettlementWithoutVerifiedReference() throws Exception {
        Booking b=create(false);quote(b);login(officer);
        var p=payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),"Bank Transfer",
                "SYNTHETIC-PENDING-"+b.getId(),PaymentStatus.PENDING,null,null);
        long notices=notificationRepo.count(),audits=paymentAudit(p.getId()).size();
        assertThrows(IllegalArgumentException.class,()->payments.updatePaymentStatus(p.getId(),PaymentStatus.PAID,null,"Synthetic note alone."));
        assertEquals(PaymentStatus.PENDING,paymentRepo.findById(p.getId()).orElseThrow().getPaymentStatus());
        assertEquals(notices,notificationRepo.count());assertEquals(audits,paymentAudit(p.getId()).size());
    }
    @Test void pendingManualAttemptCannotClaimAnotherPaymentsSettlement() throws Exception {
        Booking b=create(false);quote(b);login(officer);
        var pending=payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),"Bank Transfer",
                "SYNTHETIC-PENDING-"+b.getId(),PaymentStatus.PENDING,null,null);
        login(customer);var settled=pay(b,PaymentType.VISA_DOCUMENTATION,"40");login(officer);
        long logs=paymentAudit(settled.getId()).size();
        assertThrows(IllegalStateException.class,()->payments.updatePaymentStatus(pending.getId(),PaymentStatus.PAID,"SYNTHETIC-LATE-BANK","Synthetic late submission."));
        assertEquals(PaymentStatus.PENDING,paymentRepo.findById(pending.getId()).orElseThrow().getPaymentStatus());
        assertEquals(logs,paymentAudit(settled.getId()).size());
    }
    @Test void verifiedCheckoutBindsOnePaymentAndAllowsCancellationBeforeSessionExpiry() throws Exception {
        Booking b=create(false);var v=quote(b);var a=prepared(b,v);
        var p=payments.processProviderPayment(providerRequest(b,"pi_Synthetic"+b.getId()),a.getId(),"cs_test_Synthetic"+b.getId());
        assertEquals(p.getId(),attempts.findById(a.getId()).orElseThrow().getSettledPayment().getId());
        assertTrue(a.getExpiresAt()>clock.instant().getEpochSecond());
        var q=quotedCancellation(b,p);assertEquals("QUOTED",q.getStatus());
        login(customer);cancellations.accept(q.getId(),q.getVersion(),true);
        assertEquals(BookingStatus.CANCELLED,bookingRepo.findById(b.getId()).orElseThrow().getBookingStatus());
        assertEquals(RefundStatus.REFUND_PENDING,refundRepo.findByPayment_Id(p.getId()).orElseThrow().getStatus());
    }
    @Test void repeatedVerifiedWebhookSettlementIsIdempotentIncludingRecoveredCheckoutResponse() throws Exception {
        Booking b=create(false);var v=quote(b);var a=prepared(b,v);String pi="pi_Synthetic"+b.getId(),cs="cs_test_Synthetic"+b.getId();
        assertNull(a.getSessionReference());
        var first=payments.processProviderPayment(providerRequest(b,pi),a.getId(),cs);long count=paymentRepo.count(),notices=notificationRepo.count(),logs=paymentAudit(first.getId()).size();
        var again=payments.processProviderPayment(providerRequest(b,pi),a.getId(),cs);
        assertEquals(first.getId(),again.getId());assertEquals(count,paymentRepo.count());assertEquals(notices,notificationRepo.count());
        assertEquals(logs,paymentAudit(first.getId()).size());assertEquals(cs,attempts.findById(a.getId()).orElseThrow().getSessionReference());
        assertEquals(first.getId(),attempts.findById(a.getId()).orElseThrow().getSettledPayment().getId());
    }
    @Test void contradictoryCheckoutBindingRollsBackNewPaymentVisaAndNotificationsAtomically() throws Exception {
        Booking otherBooking=create(false);processing(otherBooking);var unrelated=paymentRepo.findByBooking_Id(otherBooking.getId()).get(0);
        Booking b=create(false);var v=quote(b);var a=prepared(b,v);
        tx.executeWithoutResult(s->attempts.findById(a.getId()).orElseThrow().setSettledPayment(unrelated));
        long count=paymentRepo.count(),notices=notificationRepo.count(),history=historyRepo.count(),logs=audit.getAllLogs().size();
        assertThrows(IllegalStateException.class,()->payments.processProviderPayment(providerRequest(b,"pi_Synthetic"+b.getId()),a.getId(),"cs_test_Synthetic"+b.getId()));
        assertEquals(count,paymentRepo.count());assertEquals(notices,notificationRepo.count());assertEquals(history,historyRepo.count());assertEquals(logs,audit.getAllLogs().size());
        assertEquals(VisaStatus.PAYMENT_PENDING,visaRepo.findById(v.getId()).orElseThrow().getStatus());
        assertEquals(unrelated.getId(),attempts.findById(a.getId()).orElseThrow().getSettledPayment().getId());
    }
    @Test void historicalUnboundAttemptStaysBlockedDespiteMatchingPaidAmountAndType() throws Exception {
        Booking b=create(false);var v=quote(b);var a=prepared(b,v);var p=payments.processPayment(providerRequest(b,"pi_Synthetic"+b.getId()));
        assertNull(attempts.findById(a.getId()).orElseThrow().getSettledPayment());
        var failure=assertThrows(IllegalStateException.class,()->quotedCancellation(b,p));
        assertTrue(failure.getMessage().contains("hosted checkout is still open"));
        assertNull(attempts.findById(a.getId()).orElseThrow().getSettledPayment());assertTrue(refundRepo.findByPayment_Id(p.getId()).isEmpty());
    }
    @Test void refundPageHidesSimulatedSettlementForRealBankRefunds() throws Exception {
        Booking b=create(false);var v=quote(b);login(officer);
        payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40"),"Bank Transfer",
                "SYNTHETIC-BANK-"+b.getId(),PaymentStatus.PAID,null,"Synthetic acceptance fixture.");visas.rejectVisa(v.getId(),"Synthetic rejection",null);
        String body=mvc.perform(get("/staff/payments/refund-settlements").with(staffUser(officer.getEmail(),"VISA_OFFICER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String card=body.substring(body.indexOf("Booking #"+b.getId()+" · Refund"));
        card=card.substring(0,card.indexOf("</section>"));
        assertTrue(card.contains("This refund remains pending."),card);assertFalse(card.contains("Record simulated settlement"),card);
    }
}
