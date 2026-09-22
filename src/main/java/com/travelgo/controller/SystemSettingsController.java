package com.travelgo.controller;

import com.travelgo.entity.SystemSettings;
import com.travelgo.service.SystemSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for System Settings in Admin panel.
 * Handles company info, contact info, business hours, and website status.
 */
@Controller
@RequestMapping("/admin/settings")
public class SystemSettingsController {

    private final SystemSettingsService settingsService;

    public SystemSettingsController(SystemSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public String viewSettings(Model model) {
        model.addAttribute("settings", settingsService.getSettings());
        return "admin/settings";
    }

    @PostMapping("/company")
    public String updateCompany(@RequestParam("companyName") String companyName,
                               @RequestParam("companyDescription") String companyDescription,
                               @RequestParam("websiteUrl") String websiteUrl,
                               RedirectAttributes redirectAttributes) {
        try {
            settingsService.updateCompanyInfo(companyName, companyDescription, websiteUrl);
            redirectAttributes.addFlashAttribute("successMessage", "Company information updated successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to update company info: " + e.getMessage());
        }
        return "redirect:/admin/settings";
    }

    @PostMapping("/contact")
    public String updateContact(@RequestParam("companyEmail") String companyEmail,
                               @RequestParam("companyPhone") String companyPhone,
                               @RequestParam("companyAddress") String companyAddress,
                               RedirectAttributes redirectAttributes) {
        try {
            settingsService.updateContactInfo(companyEmail, companyPhone, companyAddress);
            redirectAttributes.addFlashAttribute("successMessage", "Contact information updated successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to update contact info: " + e.getMessage());
        }
        return "redirect:/admin/settings";
    }

    @PostMapping("/status")
    public String updateStatus(@RequestParam("websiteStatus") String websiteStatus,
                              @RequestParam(value = "maintenanceMessage", required = false) String maintenanceMessage,
                              RedirectAttributes redirectAttributes) {
        try {
            settingsService.updateWebsiteStatus(websiteStatus, maintenanceMessage);
            redirectAttributes.addFlashAttribute("successMessage", "Website status updated successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to update website status: " + e.getMessage());
        }
        return "redirect:/admin/settings";
    }

    @PostMapping("/hours")
    public String updateHours(@RequestParam("businessHours") String businessHours,
                             RedirectAttributes redirectAttributes) {
        try {
            settingsService.updateBusinessHours(businessHours);
            redirectAttributes.addFlashAttribute("successMessage", "Business hours updated successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to update business hours: " + e.getMessage());
        }
        return "redirect:/admin/settings";
    }
}
