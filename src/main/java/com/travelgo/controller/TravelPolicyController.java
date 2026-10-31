package com.travelgo.controller;

import com.travelgo.service.TravelPolicyService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class TravelPolicyController {
    private final TravelPolicyService policies;
    public TravelPolicyController(TravelPolicyService policies){this.policies=policies;}
    @GetMapping({"/terms","/privacy","/cancellation-policy"})
    public String current(jakarta.servlet.http.HttpServletRequest request,Model model){
        var p=policies.current().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"The agency has not published its policies yet."));
        return page(p,request.getRequestURI().substring(1),model);
    }
    @GetMapping("/policies/{id}/{section}")
    public String version(@PathVariable Long id,@PathVariable String section,Model model){
        try{return page(policies.published(id),section,model);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
    }
    private String page(com.travelgo.entity.TravelPolicy p,String section,Model model){
        String title,content;
        switch(section){case "terms"-> {title="Booking terms";content=p.getTerms();}case "privacy"->{title="Privacy policy";content=p.getPrivacy();}
            case "cancellation-policy"->{title="Cancellation and refunds";content=p.getCancellation();}default->throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
        model.addAttribute("pageTitle",title);model.addAttribute("policyContent",content);model.addAttribute("policy",p);return "travel-policy";
    }
    @GetMapping("/admin/travel-policies")
    public String manage(Model model){model.addAttribute("policies",policies.list());model.addAttribute("currentPolicyId",policies.current().map(p->p.getId()).orElse(null));return "admin/travel-policies";}
    @PostMapping("/admin/travel-policies/draft")
    public String draft(@RequestParam String title,@RequestParam String terms,@RequestParam String privacy,@RequestParam String cancellation,RedirectAttributes flash){
        try{policies.draft(title,terms,privacy,cancellation);flash.addFlashAttribute("successMessage","Policy draft saved. Review it before publishing.");}catch(IllegalArgumentException|IllegalStateException e){flash.addFlashAttribute("errorMessage",e.getMessage());}return "redirect:/admin/travel-policies";
    }
    @PostMapping("/admin/travel-policies/{id}/publish")
    public String publish(@PathVariable Long id,@RequestParam(required=false) Long expectedCurrentId,@RequestParam(defaultValue="false") boolean agencyApproved,RedirectAttributes flash){
        try{policies.publish(id,expectedCurrentId,agencyApproved);flash.addFlashAttribute("successMessage","Policy published. Existing bookings retain the policy they accepted.");}catch(IllegalArgumentException|IllegalStateException e){flash.addFlashAttribute("errorMessage",e.getMessage());}return "redirect:/admin/travel-policies";
    }
}
