package com.travelgo.controller;
import com.travelgo.service.ContactMessageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class CustomerInquiryController {
    private final ContactMessageService inquiries;

    public CustomerInquiryController(ContactMessageService inquiries) {
        this.inquiries = inquiries;
    }

    @GetMapping("/customer/inquiries")
    public String history(Model model) {
        model.addAttribute("inquiries", inquiries.forCustomer());
        return "customer/inquiries";
    }

    @GetMapping("/customer/inquiries/{id}")
    public String detail(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return inquiries.forCustomer().stream()
                .filter(i -> i.getId().equals(id))
                .findFirst()
                .map(inquiry -> {
                    model.addAttribute("inquiry", inquiry);
                    return "customer/inquiry-detail";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("errorMessage", "Inquiry not found.");
                    return "redirect:/customer/inquiries";
                });
    }
}
