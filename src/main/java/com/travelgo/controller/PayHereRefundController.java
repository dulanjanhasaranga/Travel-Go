package com.travelgo.controller;
import com.travelgo.service.PayHereRefundService;
import com.travelgo.service.CustomerErrorMessage;
import java.math.BigDecimal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
@Controller
@RequestMapping("/staff/payments/refund-settlements")
public class PayHereRefundController {
    private final PayHereRefundService refunds;
    public PayHereRefundController(PayHereRefundService refunds){this.refunds=refunds;}
    @PostMapping("/{id}/payhere")
    public String submit(@PathVariable Long id,RedirectAttributes flash){try{var result=refunds.submit(id);
        flash.addFlashAttribute("successMessage","SUCCEEDED".equals(result.getStatus())?"Refund already settled.":"PayHere refund status: "+result.getStatus().replace('_',' ').toLowerCase(java.util.Locale.ROOT)+". Review settlement evidence below.");
        }catch(Exception failure){flash.addFlashAttribute("errorMessage",CustomerErrorMessage.from(failure));}return "redirect:/staff/payments/refund-settlements";}
    @PostMapping("/{id}/payhere/reconcile")
    public String reconcile(@PathVariable Long id,@RequestParam String reference,@RequestParam BigDecimal verifiedAmount,
            @RequestParam String evidence,@RequestParam(defaultValue="false") boolean verified,RedirectAttributes flash){
        try{refunds.reconcile(id,reference,verifiedAmount,evidence,verified);flash.addFlashAttribute("successMessage","PayHere refund settlement recorded.");}
        catch(Exception failure){flash.addFlashAttribute("errorMessage",CustomerErrorMessage.from(failure));}return "redirect:/staff/payments/refund-settlements";}
}
