package com.travelgo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Guarded H2 fixture, mocked provider. Inherited workflow assertions also run without provider network access. */
class PayHereFinancialWorkflowTests extends WorkflowIntegrationTests {
    @MockitoBean PayHereService payHere;
    @MockitoBean PayHereMerchantClient merchant;
    @Autowired PayHereRefundService providerRefunds;
    @Autowired ProviderRefundAttemptRepository providerAttempts;
    @Autowired ProviderCheckoutAttemptRepository checkoutRepo;
    @Autowired ProviderCheckoutAttemptService checkout;
    @BeforeEach void providerFixture(){
        when(payHere.isConfigured()).thenReturn(true);when(payHere.getMode()).thenReturn("test");when(payHere.deploymentReference()).thenReturn("payhere-synthetic-current");
    }
    private ProviderCheckoutAttempt prepare(Booking b,VisaApplication v){
        login(customer);var a=checkout.preparePayHere(b,PaymentType.VISA_DOCUMENTATION,new BigDecimal("40.00"),v.getPaymentExpiresAt(),"test","payhere-synthetic-current");
        checkout.completed(a.getId(),order(a),"https://sandbox.payhere.lk/pay/checkout");return checkoutRepo.findById(a.getId()).orElseThrow();
    }
    private String order(ProviderCheckoutAttempt a){return "TGPH-"+a.getId()+"-abcdef0123456789abcdef01";}
    private String refundReference(Refund r){return "560"+r.getId();}
    private Payment settle(Booking b,ProviderCheckoutAttempt a,String id){Payment p=new Payment();p.setBooking(b);p.setPaymentType(PaymentType.VISA_DOCUMENTATION);p.setAmount(new BigDecimal("40.00"));p.setPaymentMethod("PayHere Checkout");p.setTransactionReference("PH-"+id);
        return payments.processPayHerePayment(p,a.getId(),order(a),"test","payhere-synthetic-current");}
    private Refund rejected()throws Exception{Booking b=create(false);var v=quote(b);var a=prepare(b,v);login(customer);var p=settle(b,a,"320"+a.getId());login(officer);visas.rejectVisa(v.getId(),"Synthetic rejection reason.",null);return refundRepo.findByPayment_Id(p.getId()).orElseThrow();}
    @Test void verifiedSettlementIsBoundAndRepeatedSubmissionsAddNoEffects()throws Exception{
        Booking b=create(false);var v=quote(b);var a=prepare(b,v);login(customer);var p=settle(b,a,"320"+a.getId());long count=paymentRepo.count(),notices=notificationRepo.count(),history=historyRepo.count();
        assertEquals(p.getId(),checkoutRepo.findById(a.getId()).orElseThrow().getSettledPayment().getId());assertEquals(VisaStatus.PROCESSING,visaRepo.findById(v.getId()).orElseThrow().getStatus());
        assertEquals(p.getId(),settle(b,a,"320"+a.getId()).getId());assertEquals(count,paymentRepo.count());assertEquals(notices,notificationRepo.count());assertEquals(history,historyRepo.count());
        assertThrows(IllegalStateException.class,()->settle(b,a,"999"+a.getId()));assertEquals(count,paymentRepo.count());
    }
    @Test void paymentAtExactExpiryDoesNotSettleOrStartVisa()throws Exception{
        Booking b=create(false);var v=quote(b);var a=prepare(b,v);clock.instant=java.time.Instant.ofEpochSecond(a.getExpiresAt());login(customer);long count=paymentRepo.count();
        assertThrows(IllegalStateException.class,()->settle(b,a,"320"+a.getId()));assertEquals(count,paymentRepo.count());assertEquals(VisaStatus.PAYMENT_PENDING,visaRepo.findById(v.getId()).orElseThrow().getStatus());
    }
    @Test void ownershipAndManualProviderImpersonationAreBlocked()throws Exception{
        Booking b=create(false);var v=quote(b);var a=prepare(b,v);login(other);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->settle(b,a,"320"+a.getId()));
        login(officer);assertThrows(IllegalArgumentException.class,()->payments.recordManualPayment(b.getId(),PaymentType.VISA_DOCUMENTATION,new BigDecimal("40.00"),"PayHere Checkout","PH-12345",PaymentStatus.PAID,null,null));
    }
    @Test void ambiguousProviderResponseNeverResendsRefundAndOriginalPaymentIsPreserved()throws Exception{
        Refund r=rejected();when(payHere.verifiedPayment(any(),anyString())).thenReturn(new ObjectMapper().readTree("{\"status\":\"RECEIVED\"}"));
        when(merchant.refund(anyString(),eq("10.00"),anyString())).thenThrow(new IllegalStateException("synthetic connection loss"));
        var attempt=providerRefunds.submit(r.getId());assertEquals("REVIEW_REQUIRED",attempt.getStatus());assertEquals(attempt.getId(),providerRefunds.submit(r.getId()).getId());
        verify(merchant,times(1)).refund(anyString(),eq("10.00"),anyString());assertEquals(RefundStatus.REFUND_PENDING,refundRepo.findById(r.getId()).orElseThrow().getStatus());
        assertEquals(new BigDecimal("40.00"),paymentRepo.findById(r.getPayment().getId()).orElseThrow().getAmount());assertEquals(PaymentStatus.PAID,paymentRepo.findById(r.getPayment().getId()).orElseThrow().getPaymentStatus());
    }
    @Test void acceptedRequestNeedsCompletedProviderAndExactStaffEvidenceBeforePartialSettlement()throws Exception{
        Refund r=rejected();String reference=refundReference(r);var mapper=new ObjectMapper();when(payHere.verifiedPayment(any(),anyString())).thenReturn(mapper.readTree("{\"status\":\"RECEIVED\"}"));
        when(merchant.refund(anyString(),eq("10.00"),anyString())).thenReturn(mapper.readTree("{\"status\":1,\"data\":"+reference+"}"));
        var attempt=providerRefunds.submit(r.getId());assertEquals("PENDING",attempt.getStatus());assertEquals(RefundStatus.REFUND_PENDING,refundRepo.findById(r.getId()).orElseThrow().getStatus());
        assertThrows(IllegalArgumentException.class,()->providerRefunds.reconcile(r.getId(),reference,new BigDecimal("40.00"),"Synthetic provider settlement record",true));
        assertThrows(IllegalStateException.class,()->providerRefunds.reconcile(r.getId(),reference,new BigDecimal("10.00"),"Synthetic provider settlement record",true));
        when(payHere.verifiedPayment(any(),anyString())).thenReturn(mapper.readTree("{\"status\":\"REFUNDED\"}"));long notices=notificationRepo.count();
        assertEquals("SUCCEEDED",providerRefunds.reconcile(r.getId(),reference,new BigDecimal("10.00"),"Synthetic PayHere completed settlement record #"+reference,true).getStatus());
        assertEquals(RefundStatus.REFUND_PROCESSED,refundRepo.findById(r.getId()).orElseThrow().getStatus());assertEquals(PaymentStatus.PAID,paymentRepo.findById(r.getPayment().getId()).orElseThrow().getPaymentStatus());
        assertEquals(new BigDecimal("40.00"),paymentRepo.findById(r.getPayment().getId()).orElseThrow().getAmount());assertEquals(notices+1,notificationRepo.count());
        providerRefunds.reconcile(r.getId(),null,null,null,false);assertEquals(notices+1,notificationRepo.count());verify(merchant,times(1)).refund(anyString(),anyString(),anyString());
    }
    @Test void providerRefundActionsRequireVisaOfficerAndCsrf()throws Exception{
        Refund r=rejected();login(customer);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->providerRefunds.submit(r.getId()));
        mvc.perform(post("/staff/payments/refund-settlements/"+r.getId()+"/payhere").with(staffUser(officer.getEmail(),"VISA_OFFICER"))).andExpect(status().isForbidden());
        mvc.perform(post("/staff/payments/refund-settlements/"+r.getId()+"/payhere").with(user(customer.getEmail()).roles("CUSTOMER")).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void reusedProviderRefundReferenceRequiresReviewWithoutSettlingEitherRefund()throws Exception{
        Refund first=rejected();String reference=refundReference(first);var mapper=new ObjectMapper();when(payHere.verifiedPayment(any(),anyString())).thenReturn(mapper.readTree("{\"status\":\"RECEIVED\"}"));
        when(merchant.refund(anyString(),eq("10.00"),anyString())).thenReturn(mapper.readTree("{\"status\":1,\"data\":"+reference+"}"));
        assertEquals("PENDING",providerRefunds.submit(first.getId()).getStatus());Refund second=rejected();var result=providerRefunds.submit(second.getId());
        assertEquals("REVIEW_REQUIRED",result.getStatus());assertNull(result.getProviderRefundReference());
        assertEquals(reference,providerAttempts.findByRefund_Id(first.getId()).orElseThrow().getProviderRefundReference());
        assertEquals(RefundStatus.REFUND_PENDING,refundRepo.findById(first.getId()).orElseThrow().getStatus());
        assertEquals(RefundStatus.REFUND_PENDING,refundRepo.findById(second.getId()).orElseThrow().getStatus());
        assertTrue(context.getBean(AuditService.class).getAllLogs().stream().anyMatch(a->"PAYHERE_REFUND_REVIEW_REQUIRED".equals(a.getAction())&&second.getId().equals(a.getEntityId())&&a.getDetails().contains("DataIntegrityViolationException")));
        providerRefunds.submit(second.getId());verify(merchant,times(2)).refund(anyString(),anyString(),anyString());
    }
    @Test void reconciliationDuringProviderResponseCannotRevertCompletedRefund()throws Exception{
        Refund r=rejected();String reference=refundReference(r);var mapper=new ObjectMapper();when(payHere.verifiedPayment(any(),anyString())).thenReturn(mapper.readTree("{\"status\":\"RECEIVED\"}"));
        when(merchant.refund(anyString(),eq("10.00"),anyString())).thenAnswer(call->{
            when(payHere.verifiedPayment(any(),anyString())).thenReturn(mapper.readTree("{\"status\":\"REFUNDED\"}"));
            providerRefunds.reconcile(r.getId(),reference,new BigDecimal("10.00"),"Synthetic concurrent PayHere completed settlement record",true);
            return mapper.readTree("{\"status\":1,\"data\":"+reference+"}");
        });long notices=notificationRepo.count();var result=providerRefunds.submit(r.getId());
        assertEquals("SUCCEEDED",result.getStatus());assertEquals(RefundStatus.REFUND_PROCESSED,refundRepo.findById(r.getId()).orElseThrow().getStatus());
        assertEquals("Synthetic concurrent PayHere completed settlement record",result.getReviewNote());assertEquals(notices+1,notificationRepo.count());
        providerRefunds.submit(r.getId());assertEquals(notices+1,notificationRepo.count());verify(merchant,times(1)).refund(anyString(),anyString(),anyString());
    }
    @Test void conflictingLateResponsePreservesSettlementAndAddsDurableAudit()throws Exception{
        Refund r=rejected();String reference=refundReference(r),conflictingReference="561"+r.getId();var mapper=new ObjectMapper();when(payHere.verifiedPayment(any(),anyString())).thenReturn(mapper.readTree("{\"status\":\"RECEIVED\"}"));
        when(merchant.refund(anyString(),eq("10.00"),anyString())).thenAnswer(call->{
            when(payHere.verifiedPayment(any(),anyString())).thenReturn(mapper.readTree("{\"status\":\"REFUNDED\"}"));
            providerRefunds.reconcile(r.getId(),reference,new BigDecimal("10.00"),"Synthetic officer settlement evidence preserved",true);
            return mapper.readTree("{\"status\":1,\"data\":"+conflictingReference+"}");
        });long notices=notificationRepo.count();var result=providerRefunds.submit(r.getId());
        assertEquals("SUCCEEDED",result.getStatus());assertEquals(reference,result.getProviderRefundReference());assertTrue(result.getReviewNote().startsWith("Synthetic officer settlement evidence preserved"));
        assertTrue(result.getReviewNote().contains(conflictingReference));assertEquals("PH-REFUND-"+reference,refundRepo.findById(r.getId()).orElseThrow().getSettlementReference());
        assertEquals(RefundStatus.REFUND_PROCESSED,refundRepo.findById(r.getId()).orElseThrow().getStatus());assertEquals(notices+1,notificationRepo.count());
        assertTrue(context.getBean(AuditService.class).getAllLogs().stream().anyMatch(a->"PAYHERE_REFUND_EVIDENCE_CONFLICT".equals(a.getAction())&&r.getId().equals(a.getEntityId())));
        verify(merchant,times(1)).refund(anyString(),anyString(),anyString());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void lateProviderResponsePreservesPreviouslyRecordedOfficerReview(boolean responseLost)throws Exception{
        Refund r=rejected();var mapper=new ObjectMapper();when(payHere.verifiedPayment(any(),anyString())).thenReturn(mapper.readTree("{\"status\":\"RECEIVED\"}"));
        when(merchant.refund(anyString(),eq("10.00"),anyString())).thenAnswer(call->{
            tx.executeWithoutResult(status->{var recorded=providerAttempts.findByRefund_Id(r.getId()).orElseThrow();
                recorded.setReviewedBy(officer);recorded.setReviewNote("Synthetic officer review: verify the merchant settlement statement.");providerAttempts.saveAndFlush(recorded);});
            if(responseLost)throw new IllegalStateException("Synthetic response lost after officer review");
            return mapper.readTree("{\"status\":1,\"data\":"+refundReference(r)+"}");
        });var result=providerRefunds.submit(r.getId());
        assertEquals(responseLost?"REVIEW_REQUIRED":"PENDING",result.getStatus());
        assertEquals("Synthetic officer review: verify the merchant settlement statement.",result.getReviewNote());
        assertEquals(officer.getId(),result.getReviewedBy().getId());assertEquals(RefundStatus.REFUND_PENDING,refundRepo.findById(r.getId()).orElseThrow().getStatus());
        verify(merchant,times(1)).refund(anyString(),anyString(),anyString());
    }
    @Test void billingAndForwardingFormsRenderAndNeverExportApplicationCsrfToken()throws Exception{
        Booking b=create(false);quote(b);var controller=context.getBean(com.travelgo.controller.CustomerPaymentController.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"simulatedPayments",false);
        try{
            var page=mvc.perform(post("/customer/payments/process").with(user(customer.getEmail()).roles("CUSTOMER")).with(csrf())
                    .param("bookingId",b.getId().toString()).param("paymentType","VISA_DOCUMENTATION").param("amount","40.00")).andExpect(status().isOk()).andReturn();
            assertTrue(page.getResponse().getContentAsString().contains("Billing details"));
            when(payHere.checkout(any(),any(),any(),any(),any())).thenReturn(new PayHereService.Checkout("https://sandbox.payhere.lk/pay/checkout",Map.of("amount","40.00","hash","SYNTHETIC_HASH","merchant_id","1212345")));
            var forwarded=mvc.perform(post("/customer/payments/payhere").with(user(customer.getEmail()).roles("CUSTOMER")).with(csrf())
                    .param("bookingId",b.getId().toString()).param("paymentType","VISA_DOCUMENTATION").param("amount","40.00")
                    .param("firstName","Synthetic").param("lastName","Customer").param("email","customer@travelgo.test")
                    .param("phone","0771234567").param("address","Synthetic address").param("city","Colombo").param("country","Sri Lanka"))
                    .andExpect(status().isOk()).andReturn();
            String html=forwarded.getResponse().getContentAsString();assertTrue(html.contains("action=\"https://sandbox.payhere.lk/pay/checkout\""));
            String form=html.substring(html.indexOf("<form method=\"post\""),html.indexOf("</form>",html.indexOf("<form method=\"post\"")));
            assertFalse(form.contains("_csrf"));assertEquals("strict-origin-when-cross-origin",forwarded.getResponse().getHeader("Referrer-Policy"));assertEquals("no-store",forwarded.getResponse().getHeader("Cache-Control"));
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(controller,"simulatedPayments",true);}
    }
}
