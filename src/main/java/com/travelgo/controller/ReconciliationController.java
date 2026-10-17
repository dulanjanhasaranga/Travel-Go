package com.travelgo.controller;
import com.travelgo.entity.Booking;
import com.travelgo.service.*;
import com.travelgo.repository.PaymentRepository;
import com.travelgo.enums.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.*;

/** Read-only financial discrepancies plus an explicit provider-investigation log. No historical repair. */
@Controller @RequestMapping("/admin/reconciliation")
public class ReconciliationController {
    private final AdminAccess access;private final BookingService bookings;private final BookingSummaryService summaries;
    private final ProviderPaymentReviewService provider;private final PaymentRepository payments;private final WorkflowRules rules;
    public ReconciliationController(AdminAccess access,BookingService bookings,BookingSummaryService summaries,ProviderPaymentReviewService provider,PaymentRepository payments,WorkflowRules rules){
        this.access=access;this.bookings=bookings;this.summaries=summaries;this.provider=provider;this.payments=payments;this.rules=rules;}
    public record Issue(Booking booking,String reason){}
    @GetMapping public String page(Model model){
        access.requireAdministrator("PAYMENT_VIEW","PAYMENT_MANAGE");var issues=new ArrayList<Issue>();
        for(var b:bookings.findAll()){
            try{var s=summaries.forBooking(b);if(s.reviewRequired()){issues.add(new Issue(b,"Review this booking's visa approval and required payments before confirming travel."));continue;}
                var successful=payments.findByBooking_Id(b.getId()).stream().filter(p->p.getPaymentStatus()==PaymentStatus.PAID||p.getPaymentStatus()==PaymentStatus.REFUNDED).toList();
                if(successful.stream().map(p->p.getPaymentType()).distinct().count()!=successful.size())throw new IllegalStateException("Multiple successful payments share a payment type.");
                for(var p:successful){if(p.getPaidAt()==null)throw new IllegalStateException("A successful payment has no settlement date.");
                    if(p.getAmount()==null||p.getAmount().compareTo(rules.expected(b,p.getPaymentType()))!=0)throw new IllegalStateException("A successful payment does not match its stored booking charge.");}
            }catch(IllegalArgumentException|IllegalStateException e){issues.add(new Issue(b,e.getMessage()));}
        }
        model.addAttribute("issues",issues);model.addAttribute("providerReviews",provider.findOpen());return "admin/reconciliation";
    }
    @PostMapping("/provider/{id}/acknowledge") public String acknowledge(@PathVariable Long id,@RequestParam String note,RedirectAttributes flash){
        access.requireAdministrator("PAYMENT_MANAGE");try{provider.acknowledge(id,note);flash.addFlashAttribute("successMessage","Review note saved. Payment and refund amounts are unchanged.");}
        catch(IllegalArgumentException|IllegalStateException e){flash.addFlashAttribute("errorMessage",e.getMessage());}return "redirect:/admin/reconciliation";
    }
}
