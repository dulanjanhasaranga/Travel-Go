package com.travelgo.controller;

import com.travelgo.service.ApprovalService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public String viewApprovals(Model model) {
        var requests = approvalService.getPendingRequests();
        model.addAttribute("pendingRequests", requests);
        var changes = new java.util.LinkedHashMap<Long, java.util.Map<String, String>>();
        requests.forEach(request -> changes.put(request.getId(), approvalService.getDisplayChanges(request)));
        model.addAttribute("requestChanges", changes);
        String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        model.addAttribute("currentUserEmail", currentUserEmail);
        return "admin/approvals";
    }

    @PostMapping("/{id}/approve")
    public String approveRequest(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            approvalService.approveRequest(id, currentUserEmail);
            redirectAttributes.addFlashAttribute("successMessage", "Request approved.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/admin/approvals";
    }

    @PostMapping("/{id}/reject")
    public String rejectRequest(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            approvalService.rejectRequest(id, currentUserEmail);
            redirectAttributes.addFlashAttribute("successMessage", "Request rejected.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/admin/approvals";
    }
}
