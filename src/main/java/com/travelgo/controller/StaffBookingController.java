package com.travelgo.controller;

import com.travelgo.service.BookingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for Staff Booking management.
 * Travel Consultants can view, confirm, decline and request info on bookings.
 */
@Controller
@RequestMapping("/staff/bookings")
public class StaffBookingController {

    private final BookingService bookingService;

    public StaffBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GetMapping
    public String listBookings(Model model) {
        var bookings = bookingService.findAll();
        model.addAttribute("bookings", bookings);
        model.addAttribute("confirmableBookingIds", bookings.stream().filter(bookingService::canConfirm).map(com.travelgo.entity.Booking::getId).toList());
        return "staff/bookings";
    }

    @PostMapping("/{id}/confirm")
    public String confirmBooking(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        try {
            bookingService.confirmBooking(id);
            redirectAttributes.addFlashAttribute("successMessage", "Booking confirmed successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to confirm booking: " + e.getMessage());
        }
        return "redirect:/staff/bookings";
    }

    @PostMapping("/{id}/decline")
    public String declineBooking(@PathVariable("id") Long id,
                                 @RequestParam("reason") String reason,
                                 RedirectAttributes redirectAttributes) {
        try {
            bookingService.declineBooking(id, reason);
            redirectAttributes.addFlashAttribute("successMessage", "Booking declined. The customer has been notified.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to decline booking: " + e.getMessage());
        }
        return "redirect:/staff/bookings";
    }

    @PostMapping("/{id}/request-info")
    public String requestInfo(@PathVariable("id") Long id,
                              @RequestParam("message") String message,
                              RedirectAttributes redirectAttributes) {
        try {
            bookingService.requestInfo(id, message);
            redirectAttributes.addFlashAttribute("successMessage", "Information request sent to the customer.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to request info: " + e.getMessage());
        }
        return "redirect:/staff/bookings";
    }
}
