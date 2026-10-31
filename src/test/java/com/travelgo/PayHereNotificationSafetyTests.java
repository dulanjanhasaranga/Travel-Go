package com.travelgo;

import com.travelgo.controller.PayHereNotificationController;
import com.travelgo.entity.*;
import com.travelgo.enums.PaymentType;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PayHereNotificationSafetyTests {
    final PayHereService payHere=mock(PayHereService.class);final PaymentService payments=mock(PaymentService.class);
    final ProviderPaymentReviewService reviews=mock(ProviderPaymentReviewService.class);final UserDetailsService users=mock(UserDetailsService.class);
    final PayHereNotificationController controller=new PayHereNotificationController(payHere,payments,reviews,users);
    final Booking booking=StripeProviderReadinessTests.booking();
    final PayHereService.Notice notice=new PayHereService.Notice("TGPH-3-abcdef0123456789abcdef01","320123456789",new BigDecimal("125.50"),"USD","2","SYNTHETIC_SIGNATURE");
    final LinkedMultiValueMap<String,String> input=new LinkedMultiValueMap<>();ProviderCheckoutAttempt attempt;
    @BeforeEach void fixture(){input.clear();input.add("merchant_id","1212345");when(payHere.verifyNotification(anyMap())).thenReturn(notice);when(payHere.getMode()).thenReturn("test");when(payHere.deploymentReference()).thenReturn("payhere-synthetic");
        attempt=new ProviderCheckoutAttempt();org.springframework.test.util.ReflectionTestUtils.setField(attempt,"id",3L);attempt.setBooking(booking);attempt.setPaymentType(PaymentType.VISA_DOCUMENTATION);attempt.setAmount(new BigDecimal("125.50"));
        when(payHere.attempt(notice)).thenReturn(attempt);when(users.loadUserByUsername(booking.getUser().getEmail())).thenReturn(org.springframework.security.core.userdetails.User.withUsername(booking.getUser().getEmail()).password("unused").roles("CUSTOMER").build());}
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    @Test void verifiedEvidenceUsesStoredQuoteAndRestoresAuthentication(){var previous=SecurityContextHolder.createEmptyContext();SecurityContextHolder.setContext(previous);
        assertEquals(200,controller.notify(input).getStatusCode().value());assertSame(previous,SecurityContextHolder.getContext());
        var captured=org.mockito.ArgumentCaptor.forClass(Payment.class);verify(payments).processPayHerePayment(captured.capture(),eq(3L),eq(notice.orderId()),eq("test"),eq("payhere-synthetic"));
        assertEquals(new BigDecimal("125.50"),captured.getValue().getAmount());assertEquals("PH-320123456789",captured.getValue().getTransactionReference());verify(payHere).verifyReceivedPayment(notice,attempt);verifyNoInteractions(reviews);}
    @Test void duplicateParametersAndInvalidChecksumCannotReachLedger(){input.add("merchant_id","another");assertEquals(400,controller.notify(input).getStatusCode().value());verifyNoInteractions(payHere,payments,reviews,users);
        input.remove("merchant_id");input.add("merchant_id","1212345");when(payHere.verifyNotification(anyMap())).thenThrow(new IllegalArgumentException("private diagnostic"));assertEquals(400,controller.notify(input).getStatusCode().value());verifyNoInteractions(payments,reviews,users);}
    @Test void unavailableRetrievalAndInactiveCustomerEnterReviewWithoutPayment(){doThrow(new IllegalStateException("private retrieval diagnostic")).when(payHere).verifyReceivedPayment(notice,attempt);
        var response=controller.notify(input);assertEquals(409,response.getStatusCode().value());assertFalse(response.getBody().contains("private"));verifyNoInteractions(payments);verify(reviews).recordPayHere(eq(notice),eq(booking),eq("test"),anyString());
        doNothing().when(payHere).verifyReceivedPayment(notice,attempt);when(users.loadUserByUsername(anyString())).thenReturn(org.springframework.security.core.userdetails.User.withUsername("inactive@travelgo.test").password("unused").roles("CUSTOMER").disabled(true).build());assertEquals(409,controller.notify(input).getStatusCode().value());verifyNoInteractions(payments);}
    @Test void pendingAndChargebackNeverPretendToSettle(){var pending=new PayHereService.Notice(notice.orderId(),notice.paymentId(),notice.amount(),"USD","0",notice.signature());when(payHere.verifyNotification(anyMap())).thenReturn(pending);assertEquals(200,controller.notify(input).getStatusCode().value());verifyNoInteractions(payments,users,reviews);
        var disputed=new PayHereService.Notice(notice.orderId(),notice.paymentId(),notice.amount(),"USD","-3",notice.signature());when(payHere.verifyNotification(anyMap())).thenReturn(disputed);assertEquals(200,controller.notify(input).getStatusCode().value());verify(reviews).recordPayHere(eq(disputed),isNull(),eq("test"),contains("chargeback"));verifyNoInteractions(payments,users);}
    @Test void routeOnlyAcceptsPostEncodedForm()throws Exception{MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();mvc.perform(get("/api/webhooks/payhere")).andExpect(status().isMethodNotAllowed());mvc.perform(post("/api/webhooks/payhere").contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnsupportedMediaType());verifyNoInteractions(payHere,payments,reviews,users);}
}
