package com.travelgo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelgo.dto.PayHereBillingRequest;
import com.travelgo.entity.*;
import com.travelgo.enums.PaymentType;
import com.travelgo.repository.ProviderCheckoutAttemptRepository;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PayHereProviderSafetyTests {
    final ProviderCheckoutAttemptService attempts=mock(ProviderCheckoutAttemptService.class);
    final ProviderCheckoutAttemptRepository repository=mock(ProviderCheckoutAttemptRepository.class);
    final PayHereMerchantClient merchant=mock(PayHereMerchantClient.class);
    final Clock clock=Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"),ZoneOffset.UTC);
    final PayHereService service=configured(true,false,false,true,"test","https://travelgo.test");
    final Booking booking=StripeProviderReadinessTests.booking();
    ProviderCheckoutAttempt attempt;
    PayHereService configured(boolean enabled,boolean simulated,boolean stripe,boolean usd,String mode,String origin){
        return new PayHereService(enabled,simulated,stripe,usd,mode,"1212345","SyntheticMerchantSecret123456",
                "SyntheticAppId123456","SyntheticAppSecret123456",origin,attempts,repository,merchant,clock);
    }
    @BeforeEach void fixture(){
        attempt=new ProviderCheckoutAttempt();ReflectionTestUtils.setField(attempt,"id",3L);attempt.setBooking(booking);
        attempt.setAmount(new BigDecimal("125.50"));attempt.setPaymentType(PaymentType.VISA_DOCUMENTATION);
        attempt.setPaymentMode("test");attempt.setDeploymentReference(service.deploymentReference());attempt.setCreatedAt(clock.instant());
        attempt.setQuoteDeadline(LocalDateTime.now(clock).plusHours(24));attempt.setExpiresAt(clock.instant().plusSeconds(23*3600).getEpochSecond());
        attempt.setSessionReference(service.orderId(attempt));when(repository.findById(3L)).thenReturn(Optional.of(attempt));
        when(attempts.preparePayHere(any(),any(),any(),any(),any(),any())).thenReturn(attempt);
    }
    Map<String,String> notice(){Map<String,String> p=new HashMap<>(Map.of("merchant_id","1212345","order_id",service.orderId(attempt),"payment_id","320012345678",
            "payhere_amount","125.50","payhere_currency","USD","status_code","2"));
        p.put("md5sig",PayHereService.md5(p.get("merchant_id")+p.get("order_id")+p.get("payhere_amount")+p.get("payhere_currency")+p.get("status_code")+PayHereService.md5("SyntheticMerchantSecret123456")));return p;}
    @Test void modeMerchantAndUsdApprovalAreExplicit(){
        assertDoesNotThrow(service::validateConfiguration);
        assertThrows(IllegalStateException.class,()->configured(true,true,false,true,"test","https://travelgo.test").validateConfiguration());
        assertThrows(IllegalStateException.class,()->configured(true,false,true,true,"test","https://travelgo.test").validateConfiguration());
        assertThrows(IllegalStateException.class,()->configured(true,false,false,false,"test","https://travelgo.test").validateConfiguration());
        assertThrows(IllegalStateException.class,()->configured(true,false,false,true,"live","http://localhost:8080").validateConfiguration());
        assertThrows(IllegalStateException.class,()->configured(true,false,false,true,"test","https://travelgo.test/other").validateConfiguration());
        assertFalse(configured(false,true,false,false,"test","http://localhost:8080").isConfigured());verifyNoInteractions(merchant);
    }
    @Test void checkoutPostsServerQuoteAndHashWithoutExposingSecrets(){
        PayHereBillingRequest b=new PayHereBillingRequest();b.setAmount(attempt.getAmount());b.setFirstName("Synthetic");b.setLastName("Customer");b.setEmail("customer@travelgo.test");b.setPhone("0771234567");b.setAddress("Synthetic address");b.setCity("Colombo");b.setCountry("Sri Lanka");
        var checkout=service.checkout(booking,PaymentType.VISA_DOCUMENTATION,attempt.getAmount(),attempt.getQuoteDeadline(),b);
        assertEquals("https://sandbox.payhere.lk/pay/checkout",checkout.action());assertEquals("125.50",checkout.fields().get("amount"));assertEquals("USD",checkout.fields().get("currency"));
        assertEquals(PayHereService.md5("1212345"+service.orderId(attempt)+"125.50USD"+PayHereService.md5("SyntheticMerchantSecret123456")),checkout.fields().get("hash"));
        assertTrue(checkout.fields().get("notify_url").equals("https://travelgo.test/api/webhooks/payhere"));assertFalse(checkout.toString().contains("SyntheticMerchantSecret"));assertFalse(checkout.toString().contains("SyntheticAppSecret"));
        b.setAmount(new BigDecimal("1.00"));assertThrows(IllegalArgumentException.class,()->service.checkout(booking,PaymentType.VISA_DOCUMENTATION,attempt.getAmount(),attempt.getQuoteDeadline(),b));
        verify(attempts,times(1)).preparePayHere(any(),any(),any(),any(),any(),any());verifyNoInteractions(merchant);
    }
    @Test void tamperedAmountStatusCurrencyMerchantAndSignatureAreRejected(){
        for(var field:Map.of("payhere_amount","0.01","status_code","0","payhere_currency","LKR","merchant_id","9999999","md5sig","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").entrySet()){
            var p=notice();p.put(field.getKey(),field.getValue());assertThrows(IllegalArgumentException.class,()->service.verifyNotification(p));}
        var valid=service.verifyNotification(notice());assertSame(attempt,service.attempt(valid));
        attempt.setDeploymentReference("payhere-old-account");assertThrows(IllegalArgumentException.class,()->service.attempt(valid));verifyNoInteractions(merchant);
    }
    String retrieved(String paymentId,String currency,String status,String date,int copies){String item="{\"payment_id\":\""+paymentId+"\",\"order_id\":\""+service.orderId(attempt)+"\",\"amount\":125.50,\"currency\":\""+currency+"\",\"status\":\""+status+"\",\"date\":\""+date+"\"}";return "{\"status\":1,\"data\":["+String.join(",",Collections.nCopies(copies,item))+"]}";}
    void response(String value)throws Exception{when(merchant.payment(anyString())).thenReturn(new ObjectMapper().readTree(value));}
    @Test void paymentIdNotCoveredByChecksumMustMatchAuthenticatedRetrieval()throws Exception{
        var p=notice();p.put("payment_id","999999999999");var valid=service.verifyNotification(p); // Provider checksum deliberately does not cover this field.
        response(retrieved("320012345678","USD","RECEIVED","2030-01-01 17:30:00",1));
        assertThrows(IllegalStateException.class,()->service.verifyReceivedPayment(valid,attempt));
        service.verifyReceivedPayment(service.verifyNotification(notice()),attempt);
        response(retrieved("320012345678","LKR","RECEIVED","2030-01-01 17:30:00",1));assertThrows(IllegalStateException.class,()->service.verifyReceivedPayment(service.verifyNotification(notice()),attempt));
        response(retrieved("320012345678","USD","RECEIVED","2030-01-01 17:30:00",2));assertThrows(IllegalStateException.class,()->service.verifyReceivedPayment(service.verifyNotification(notice()),attempt));
    }
    @Test void providerRefundOrDeadlineBoundaryRequiresReconciliation()throws Exception{
        response(retrieved("320012345678","USD","REFUNDED","2030-01-01 17:30:00",1));assertThrows(IllegalStateException.class,()->service.verifyReceivedPayment(service.verifyNotification(notice()),attempt));
        response(retrieved("320012345678","USD","RECEIVED","provider wall time is not used",1));
        attempt.setExpiresAt(clock.instant().getEpochSecond());assertThrows(IllegalStateException.class,()->service.verifyReceivedPayment(service.verifyNotification(notice()),attempt));
        attempt.setExpiresAt(clock.instant().plusSeconds(60).getEpochSecond());attempt.setQuoteDeadline(LocalDateTime.now(clock));assertThrows(IllegalStateException.class,()->service.verifyReceivedPayment(service.verifyNotification(notice()),attempt));
    }
}
