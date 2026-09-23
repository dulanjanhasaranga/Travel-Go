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
        model.addAttribute("pendingRequests", approvalService.getPendingRequests());
        String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        model.addAttribute("currentUserEmail", currentUserEmail);
        return "admin/approvals";
    }

    @PostMapping("/{id}/approve")
    public String approveRequest(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            approvalService.approveRequest(id, currentUserEmail);
            redirectAttributes.addFlashAttribute("successMessage", "Request approved successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Approval failed: " + e.getMessage());
        }
        return "redirect:/admin/approvals";
    }

    @PostMapping("/{id}/reject")
    public String rejectRequest(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            String currentUserEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            approvalService.rejectRequest(id, currentUserEmail);
            redirectAttributes.addFlashAttribute("successMessage", "Request rejected successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Rejection failed: " + e.getMessage());
        }
        return "redirect:/admin/approvals";
    }
}
