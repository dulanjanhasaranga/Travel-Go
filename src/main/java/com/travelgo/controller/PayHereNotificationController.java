package com.travelgo.controller;

import com.travelgo.entity.*;
import com.travelgo.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@ConditionalOnProperty(name="travelgo.payhere.enabled",havingValue="true")
@RequestMapping("/api/webhooks/payhere")
public class PayHereNotificationController {
    private final PayHereService payHere;private final PaymentService payments;
    private final ProviderPaymentReviewService reviews;private final UserDetailsService users;
    public PayHereNotificationController(PayHereService payHere,PaymentService payments,ProviderPaymentReviewService reviews,UserDetailsService users){this.payHere=payHere;this.payments=payments;this.reviews=reviews;this.users=users;}
    @PostMapping(consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> notify(@RequestParam MultiValueMap<String,String> input){
        Map<String,String> values=new LinkedHashMap<>();
        if(input.size()>30||input.entrySet().stream().anyMatch(e->e.getValue().size()!=1||e.getKey().length()>100||e.getValue().get(0).length()>2000))
            return ResponseEntity.badRequest().body("Invalid PayHere notification fields.");
        input.forEach((k,v)->values.put(k,v.get(0)));
        PayHereService.Notice notice;
        try{notice=payHere.verifyNotification(values);}catch(IllegalArgumentException invalid){return ResponseEntity.badRequest().body("Invalid PayHere notification signature or fields.");}
        catch(IllegalStateException unavailable){return ResponseEntity.status(503).body("PayHere is unavailable.");}
        if(!"2".equals(notice.status())){
            if("-3".equals(notice.status())){try{reviews.recordPayHere(notice,null,payHere.getMode(),"PayHere reported a chargeback. Review the provider account; no automatic ledger reversal was made.");}catch(Exception failure){return ResponseEntity.internalServerError().body("Provider review could not be saved. Retry this notification.");}}
            return ResponseEntity.ok("Payment not settled");
        }
        var previous=SecurityContextHolder.getContext();Booking booking=null;
        try{
            ProviderCheckoutAttempt attempt=payHere.attempt(notice);booking=attempt.getBooking();
            payHere.verifyReceivedPayment(notice,attempt);
            var customer=users.loadUserByUsername(booking.getUser().getEmail());
            if(!customer.isEnabled()||customer.getAuthorities().stream().noneMatch(a->"ROLE_CUSTOMER".equals(a.getAuthority())))
                throw new IllegalStateException("Customer account requires reconciliation.");
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(customer,null,customer.getAuthorities()));SecurityContextHolder.setContext(context);
            Payment request=new Payment();request.setBooking(booking);request.setPaymentType(attempt.getPaymentType());
            request.setAmount(attempt.getAmount());request.setPaymentMethod("PayHere Checkout");request.setTransactionReference("PH-"+notice.paymentId());
            payments.processPayHerePayment(request,attempt.getId(),notice.orderId(),payHere.getMode(),payHere.deploymentReference());
            return ResponseEntity.ok("Recorded");
        }catch(Exception failure){
            try{reviews.recordPayHere(notice,booking,payHere.getMode(),"PayHere payment could not be matched to the current booking and checkout. Inspect provider evidence and workflow deadlines.");}
            catch(Exception unavailable){return ResponseEntity.internalServerError().body("Provider review could not be saved. Retry this notification.");}
            return ResponseEntity.status(failure instanceof IllegalArgumentException?400:409).body("This payment requires staff reconciliation. No additional payment was recorded.");
        }finally{SecurityContextHolder.setContext(previous);}
    }
}
