package com.travelgo.controller;

import com.travelgo.entity.Payment;
import com.travelgo.entity.VisaApplication;
import com.travelgo.service.PaymentService;
import com.travelgo.service.VisaApplicationService;
import com.travelgo.service.VisaDocumentService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Controller for Staff Visa management.
 * Visa Officers can verify documents, set charges, process, approve, and reject visas.
 */
@Controller
@RequestMapping("/staff/visas")
public class StaffVisaController {

    private final VisaApplicationService visaApplicationService;
    private final VisaDocumentService visaDocumentService;
    private final PaymentService paymentService;

    public StaffVisaController(VisaApplicationService visaApplicationService,
                              VisaDocumentService visaDocumentService,
                              PaymentService paymentService) {
        this.visaApplicationService = visaApplicationService;
        this.visaDocumentService = visaDocumentService;
        this.paymentService = paymentService;
    }

    @GetMapping
    public String listVisas(Model model) {
        List<VisaApplication> apps = visaApplicationService.findAll();
        Map<Long, List<com.travelgo.entity.VisaDocument>> documentsMap = new HashMap<>();
        for (VisaApplication app : apps) {
            documentsMap.put(app.getId(), visaDocumentService.findByVisaApplicationId(app.getId()));
        }
        model.addAttribute("visaApplications", apps);
        model.addAttribute("documentsMap", documentsMap);
        return "staff/visas";
    }

    @PostMapping("/documents/{docId}/verify")
    public String verifyDocument(@PathVariable("docId") Long docId, RedirectAttributes redirectAttributes) {
        try {
            visaDocumentService.verifyDocument(docId);
            redirectAttributes.addFlashAttribute("successMessage", "Document verified successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to verify document: " + e.getMessage());
        }
        return "redirect:/staff/visas";
    }

    @PostMapping("/{id}/verify-all")
    public String verifyAllDocuments(@PathVariable("id") Long id,
                                    @RequestParam(value = "redirect", required = false) String redirect,
                                    RedirectAttributes redirectAttributes) {
        try {
            visaApplicationService.markDocumentsVerified(id);
            redirectAttributes.addFlashAttribute("successMessage", "Documents marked as verified.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to mark documents as verified: " + e.getMessage());
        }
        return "dashboard".equalsIgnoreCase(redirect) ? "redirect:/staff/dashboard" : "redirect:/staff/visas";
    }

    @PostMapping("/{id}/charges")
    public String setCharges(@PathVariable("id") Long id,
                            @RequestParam("visaCharge") BigDecimal visaCharge,
                            @RequestParam("documentationCharge") BigDecimal documentationCharge,
                            @RequestParam(value = "redirect", required = false) String redirect,
                            RedirectAttributes redirectAttributes) {
        try {
            visaApplicationService.setVisaCharges(id, visaCharge, documentationCharge);
            redirectAttributes.addFlashAttribute("successMessage", "Visa charges issued successfully according to package specifications.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to set charges: " + e.getMessage());
        }
        return "dashboard".equalsIgnoreCase(redirect) ? "redirect:/staff/dashboard" : "redirect:/staff/visas";
    }

    @PostMapping("/{id}/process")
    public String processVisa(@PathVariable("id") Long id,
                             @RequestParam(value = "redirect", required = false) String redirect,
                             RedirectAttributes redirectAttributes) {
        try {
            visaApplicationService.startProcessing(id);
            redirectAttributes.addFlashAttribute("successMessage", "Visa processing started.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to process visa: " + e.getMessage());
        }
        return "dashboard".equalsIgnoreCase(redirect) ? "redirect:/staff/dashboard" : "redirect:/staff/visas";
    }

    @PostMapping("/{id}/approve")
    public String approveVisa(@PathVariable("id") Long id,
                              @RequestParam(value = "redirect", required = false) String redirect,
                              RedirectAttributes redirectAttributes) {
        try {
            visaApplicationService.approveVisa(id);
            redirectAttributes.addFlashAttribute("successMessage", "Visa approved successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to approve visa: " + e.getMessage());
        }
        return "dashboard".equalsIgnoreCase(redirect) ? "redirect:/staff/dashboard" : "redirect:/staff/visas";
    }

    @PostMapping("/{id}/reject")
    public String rejectVisa(@PathVariable("id") Long id,
                            @RequestParam(value = "rejectionReason", required = false) String reason,
                            RedirectAttributes redirectAttributes) {
        try {
            visaApplicationService.rejectVisa(id, reason, null);
            redirectAttributes.addFlashAttribute("successMessage", "Visa rejected. Booking cancelled and documentation charge refunded.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to reject visa: " + e.getMessage());
        }
        return "redirect:/staff/visas";
    }
    @PostMapping("/{id}/reissue")
    public String reissue(@PathVariable("id") Long id, RedirectAttributes attributes) {
        try { visaApplicationService.reissueCharges(id); attributes.addFlashAttribute("successMessage", "Unchanged charges reissued for 24 hours."); }
        catch (IllegalArgumentException | IllegalStateException e) { attributes.addFlashAttribute("errorMessage", e.getMessage()); }
        return "redirect:/staff/visas";
    }
    @PostMapping("/{id}/request-documents")
    public String requestDocuments(@PathVariable Long id, @RequestParam String reason, RedirectAttributes attributes) {
        try {
            visaApplicationService.requestDocuments(id, reason);
            attributes.addFlashAttribute("successMessage", "The customer has been notified about the required documents.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            attributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/staff/visas";
    }
}
