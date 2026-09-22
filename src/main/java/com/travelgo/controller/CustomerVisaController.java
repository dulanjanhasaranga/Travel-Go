package com.travelgo.controller;

import com.travelgo.entity.Booking;
import com.travelgo.entity.User;
import com.travelgo.entity.VisaApplication;
import com.travelgo.entity.VisaDocument;
import com.travelgo.enums.VisaType;
import com.travelgo.service.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import com.travelgo.enums.BookingStatus;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controller for Customer Visa operations.
 * Enforces customer ownership on visa-related actions.
 */
@Controller
@RequestMapping("/customer/visas")
public class CustomerVisaController {

    private final VisaApplicationService visaApplicationService;
    private final VisaDocumentService visaDocumentService;
    private final BookingService bookingService;
    private final UserService userService;

    private static final String UPLOAD_DIR = "uploads/visa-documents/";

    public CustomerVisaController(VisaApplicationService visaApplicationService,
                                 VisaDocumentService visaDocumentService,
                                 BookingService bookingService,
                                 UserService userService) {
        this.visaApplicationService = visaApplicationService;
        this.visaDocumentService = visaDocumentService;
        this.bookingService = bookingService;
        this.userService = userService;
    }

    private User resolveUser(UserDetails userDetails) {
        if (userDetails == null) return null;
        return userService.getUserByEmail(userDetails.getUsername()).orElse(null);
    }

    @GetMapping
    public String listVisas(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        List<VisaApplication> visas = visaApplicationService.findByUserId(user.getId());
        model.addAttribute("visaApplications", visas);
        model.addAttribute("currentUser", user);

        // Find customer bookings that do not have a visa application yet
        Set<Long> visaBookingIds = visas.stream()
                .map(v -> v.getBooking().getId())
                .collect(Collectors.toSet());

        List<Booking> bookingsWithoutVisa = bookingService.findByUserId(user.getId()).stream()
                .filter(b -> !visaBookingIds.contains(b.getId()))
                .filter(bookingService::canCustomerEdit)
                .collect(Collectors.toList());

        model.addAttribute("bookingsWithoutVisa", bookingsWithoutVisa);
        return "customer/visas";
    }

    @PostMapping("/apply")
    public String applyForVisa(@RequestParam("bookingId") Long bookingId,
                              @RequestParam("visaType") String visaType,
                              @AuthenticationPrincipal UserDetails userDetails,
                              RedirectAttributes redirectAttributes) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        try {
            Booking booking = bookingService.findById(bookingId)
                    .orElseThrow(() -> new IllegalArgumentException("Booking not found"));

            // Ownership check
            if (!booking.getUser().getId().equals(user.getId())) {
                redirectAttributes.addFlashAttribute("errorMessage", "Access denied.");
                return "redirect:/customer/visas";
            }

            // Duplicate check
            if (visaApplicationService.findByUserId(user.getId()).stream().anyMatch(v -> v.getBooking().getId().equals(bookingId))) {
                redirectAttributes.addFlashAttribute("errorMessage", "A visa application already exists for this booking.");
                return "redirect:/customer/visas";
            }

            VisaType type = VisaType.valueOf(visaType);
            visaApplicationService.createVisaApplication(booking, type);
            redirectAttributes.addFlashAttribute("successMessage", "Visa application submitted successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to apply for visa: " + e.getMessage());
        }
        return "redirect:/customer/visas";
    }

    @PostMapping("/{id}/documents")
    public String uploadDocuments(@PathVariable("id") Long visaId,
                                @RequestParam("documentName") String documentName,
                                @RequestParam("file") MultipartFile file,
                                @AuthenticationPrincipal UserDetails userDetails,
                                RedirectAttributes redirectAttributes) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        try {
            visaDocumentService.upload(visaId, documentName, file);

            redirectAttributes.addFlashAttribute("successMessage", "Document uploaded successfully.");
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "File upload failed: " + e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to upload document: " + e.getMessage());
        }
        return "redirect:/customer/visas";
    }
}
