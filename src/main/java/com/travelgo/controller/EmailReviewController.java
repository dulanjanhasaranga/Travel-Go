package com.travelgo.controller;

import com.travelgo.service.EmailReviewService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/email-delivery")
public class EmailReviewController {
    private final EmailReviewService review;
    public EmailReviewController(EmailReviewService review) { this.review = review; }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') and hasAnyAuthority('PERM_SYSTEM_SETTINGS_VIEW','PERM_SYSTEM_SETTINGS_MANAGE')")
    public String list(@RequestParam(defaultValue="REVIEW_REQUIRED") String status,
            @RequestParam(defaultValue="0") int page, Model model) {
        try { model.addAttribute("messages", review.findPage(status, page)); }
        catch (IllegalArgumentException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Choose a valid delivery status and page.");
        }
        model.addAttribute("deliveryStatuses", EmailReviewService.STATUSES);
        model.addAttribute("selectedStatus", status);
        return "admin/email-delivery";
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('PERM_SYSTEM_SETTINGS_MANAGE')")
    public String review(@PathVariable Long id, @RequestParam String action,
            @RequestParam(defaultValue="") String evidenceReference, @RequestParam String note,
            @RequestParam(defaultValue="false") boolean providerChecked, RedirectAttributes flash) {
        try {
            String status = review.review(id, action, evidenceReference, note, providerChecked);
            String result = switch (status) {
                case "QUEUED" -> "Message returned to the delivery queue. Dispatch must be enabled before it is sent.";
                case "SENT" -> "Provider acceptance recorded. This does not confirm inbox delivery.";
                case "SUPPRESSED_EXPIRED", "SUPPRESSED_REVOKED" -> "The account link is no longer valid. Request a new account email if needed.";
                default -> "Message suppressed. This cannot recall a message already accepted by the provider.";
            };
            flash.addFlashAttribute("successMessage", result);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            flash.addFlashAttribute("errorMessage", invalid.getMessage());
        }
        return "redirect:/admin/email-delivery";
    }
}
