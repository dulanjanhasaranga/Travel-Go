package com.travelgo.controller;
import com.travelgo.entity.*;
import com.travelgo.enums.PaymentStatus;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Controller
public class CancellationRequestController {
    private final CancellationRequestService cancellations;private final BookingService bookings;private final WorkflowRules rules;
    private final CancellationRequestRepository requests;private final PaymentRepository payments;private final TravelPolicyService policies;
    private final OfflineRefundSettlementService offlineRefunds;
    public CancellationRequestController(CancellationRequestService cancellations,BookingService bookings,WorkflowRules rules,CancellationRequestRepository requests,PaymentRepository payments,TravelPolicyService policies,OfflineRefundSettlementService offlineRefunds){
        this.cancellations=cancellations;this.bookings=bookings;this.rules=rules;this.requests=requests;this.payments=payments;this.policies=policies;
        this.offlineRefunds=offlineRefunds;
    }
    @GetMapping("/customer/bookings/{id}/cancellation")
    public String customer(@PathVariable Long id,Model model){Booking b=bookings.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        try{rules.owner(b);}catch(org.springframework.security.access.AccessDeniedException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
        model.addAttribute("booking",b);model.addAttribute("requests",cancellations.forBooking(b));return "customer/cancellation";}
    @PostMapping("/customer/bookings/{id}/cancellation")
    public String request(@PathVariable Long id,@RequestParam String reason,RedirectAttributes flash){return customerAction(id,flash,()->cancellations.request(id,reason),"Cancellation requested. Your booking remains active until you accept a cancellation quote.");}
    @PostMapping("/customer/bookings/{bookingId}/cancellation/{id}/accept")
    public String accept(@PathVariable Long bookingId,@PathVariable Long id,@RequestParam Long version,@RequestParam(defaultValue="false") boolean accepted,RedirectAttributes flash){
        belongs(id,bookingId);return customerAction(bookingId,flash,()->cancellations.accept(id,version,accepted),"Booking cancelled. Agreed refunds are pending.");}
    @PostMapping("/customer/bookings/{bookingId}/cancellation/{id}/withdraw")
    public String withdraw(@PathVariable Long bookingId,@PathVariable Long id,@RequestParam Long version,@RequestParam String response,RedirectAttributes flash){
        belongs(id,bookingId);return customerAction(bookingId,flash,()->cancellations.close(id,version,response,true),"Cancellation request withdrawn.");}
    @GetMapping("/staff/bookings/cancellations")
    public String staff(Model model){model.addAttribute("requests",cancellations.staffList());return "staff/cancellations";}
    @GetMapping("/staff/bookings/cancellations/{id}")
    public String quotePage(@PathVariable Long id,Model model){rules.requireRole("TRAVEL_CONSULTANT");var r=requests.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("request",r);model.addAttribute("payments",payments.findByBooking_Id(r.getBooking().getId()).stream().filter(p->p.getPaymentStatus()==PaymentStatus.PAID).toList());
        model.addAttribute("quotePolicy",r.getBooking().getAcceptedPolicy()!=null?r.getBooking().getAcceptedPolicy():policies.current().orElse(null));return "staff/cancellation-quote";}
    @PostMapping("/staff/bookings/cancellations/{id}/quote")
    public String quote(@PathVariable Long id,@RequestParam Long version,@RequestParam Long policyId,@RequestParam(required=false) List<Long> paymentIds,
          @RequestParam(required=false) List<BigDecimal> refundAmounts,@RequestParam String response,
          @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime expiresAt,RedirectAttributes flash){
        try{Map<Long,BigDecimal> allocations=new LinkedHashMap<>();if(paymentIds!=null){if(refundAmounts==null||paymentIds.size()!=refundAmounts.size())throw new IllegalArgumentException("Match each payment to a refund amount.");
            for(int i=0;i<paymentIds.size();i++)if(allocations.put(paymentIds.get(i),refundAmounts.get(i))!=null)throw new IllegalArgumentException("A payment cannot appear twice in the quote.");}
            cancellations.quote(id,version,policyId,allocations,response,expiresAt);flash.addFlashAttribute("successMessage","Cancellation quote sent for customer review.");
        }catch(IllegalArgumentException|IllegalStateException e){flash.addFlashAttribute("errorMessage",e.getMessage());}return "redirect:/staff/bookings/cancellations/"+id;}
    @PostMapping("/staff/bookings/cancellations/{id}/decline")
    public String decline(@PathVariable Long id,@RequestParam Long version,@RequestParam String response,RedirectAttributes flash){
        try{cancellations.close(id,version,response,false);flash.addFlashAttribute("successMessage","Cancellation request declined. Your response has been saved.");}catch(IllegalArgumentException|IllegalStateException e){flash.addFlashAttribute("errorMessage",e.getMessage());}return "redirect:/staff/bookings/cancellations/"+id;}
    @GetMapping("/staff/payments/refund-settlements") public String pending(Model model){model.addAttribute("refunds",cancellations.pendingRefunds());model.addAttribute("offlineSettlements",offlineRefunds.recentSettlements());return "staff/refund-settlements";}
    @PostMapping("/staff/payments/refund-settlements/{id}/offline")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('VISA_OFFICER') and hasAnyAuthority('PERM_VISA_MANAGE','PERM_PAYMENT_MANAGE')")
    public String settleOffline(@PathVariable Long id, @RequestParam String channel, @RequestParam String reference,
            @RequestParam BigDecimal verifiedAmount, @RequestParam String currency,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime reimbursedAt,
            @RequestParam String evidence, @RequestParam(defaultValue="false") boolean verified, RedirectAttributes flash) {
        try { offlineRefunds.record(id,channel,reference,verifiedAmount,currency,reimbursedAt,evidence,verified);
            flash.addFlashAttribute("successMessage","Completed offline reimbursement recorded. TravelGO did not initiate a transfer.");
        } catch (IllegalArgumentException|IllegalStateException failure) { flash.addFlashAttribute("errorMessage",failure.getMessage()); }
        catch (org.springframework.dao.DataIntegrityViolationException duplicate) { flash.addFlashAttribute("errorMessage","This reimbursement evidence is already recorded or its source changed. Review the saved refund records before retrying."); }
        return "redirect:/staff/payments/refund-settlements";
    }
    @PostMapping("/staff/payments/refund-settlements/{id}") public String settle(@PathVariable Long id,@RequestParam String reference,RedirectAttributes flash){
        try{cancellations.settleSimulatedRefund(id,reference);flash.addFlashAttribute("successMessage","Simulated refund recorded. No real money was transferred.");}catch(IllegalArgumentException|IllegalStateException e){flash.addFlashAttribute("errorMessage",e.getMessage());}return "redirect:/staff/payments/refund-settlements";}
    private void belongs(Long id,Long bookingId){var r=requests.findById(id).filter(value->value.getBooking().getId().equals(bookingId)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        try{rules.owner(r.getBooking());}catch(org.springframework.security.access.AccessDeniedException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}}
    private String customerAction(Long id,RedirectAttributes flash,java.util.function.Supplier<?> action,String success){try{action.get();flash.addFlashAttribute("successMessage",success);}
        catch(IllegalArgumentException|IllegalStateException e){flash.addFlashAttribute("errorMessage",e.getMessage());}return "redirect:/customer/bookings/"+id+"/cancellation";}
}
