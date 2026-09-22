package com.travelgo.controller;

import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.enums.PaymentStatus;
import com.travelgo.enums.PaymentType;
import com.travelgo.service.BookingService;
import com.travelgo.service.PaymentService;
import com.travelgo.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller for dedicated Staff Payment Management & Ledger operations.
 * Allows Visa Officers to review transactions, record offline/previous payments,
 * update payment statuses, and audit financial records.
 */
@Controller
@RequestMapping("/staff/payments")
public class StaffPaymentController {

    private final PaymentService paymentService;
    private final BookingService bookingService;
    private final UserService userService;

    public StaffPaymentController(PaymentService paymentService,
                                  BookingService bookingService,
                                  UserService userService) {
        this.paymentService = paymentService;
        this.bookingService = bookingService;
        this.userService = userService;
    }

    @GetMapping
    public String listPayments(@RequestParam(value = "tab", required = false, defaultValue = "all") String tab,
                               @RequestParam(value = "search", required = false) String search,
                               @AuthenticationPrincipal UserDetails userDetails,
                               Model model) {
        if (userDetails != null) {
            userService.getUserByEmail(userDetails.getUsername()).ifPresent(u -> model.addAttribute("currentUser", u));
        }

        List<Payment> allPayments = paymentService.findAll();

        // Filter by tab
        List<Payment> filtered;
        switch (tab.toLowerCase()) {
            case "visa":
                filtered = allPayments.stream()
                        .filter(p -> p.getPaymentType() == PaymentType.VISA_DOCUMENTATION)
                        .collect(Collectors.toList());
                break;
            case "package":
                filtered = allPayments.stream()
                        .filter(p -> p.getPaymentType() == PaymentType.FULL_PACKAGE)
                        .collect(Collectors.toList());
                break;
            case "pending":
                filtered = allPayments.stream()
                        .filter(p -> p.getPaymentStatus() == PaymentStatus.PENDING)
                        .collect(Collectors.toList());
                break;
            case "paid":
                filtered = allPayments.stream()
                        .filter(p -> p.getPaymentStatus() == PaymentStatus.PAID)
                        .collect(Collectors.toList());
                break;
            case "history":
                filtered = allPayments; // Complete audit log
                break;
            case "all":
            default:
                filtered = allPayments;
                break;
        }

        // Apply text search if provided
        if (search != null && !search.trim().isEmpty()) {
            String query = search.trim().toLowerCase();
            filtered = filtered.stream()
                    .filter(p -> (p.getTransactionReference() != null && p.getTransactionReference().toLowerCase().contains(query))
                            || (p.getBooking() != null && p.getBooking().getId().toString().contains(query))
                            || (p.getBooking() != null && p.getBooking().getUser() != null && p.getBooking().getUser().getName().toLowerCase().contains(query))
                            || (p.getBooking() != null && p.getBooking().getUser() != null && p.getBooking().getUser().getEmail().toLowerCase().contains(query))
                            || (p.getPaymentMethod() != null && p.getPaymentMethod().toLowerCase().contains(query)))
                    .collect(Collectors.toList());
        }

        model.addAttribute("payments", filtered);
        model.addAttribute("activeTab", tab);
        model.addAttribute("searchQuery", search != null ? search : "");

        // Financial Metrics
        model.addAttribute("totalRevenue", paymentService.getTotalRevenue());
        model.addAttribute("pendingReceivables", paymentService.getPendingReceivables());
        model.addAttribute("visaPaymentsTotal", paymentService.getVisaPaymentsTotal());
        model.addAttribute("packagePaymentsTotal", paymentService.getPackagePaymentsTotal());

        model.addAttribute("totalPaymentsCount", allPayments.size());
        model.addAttribute("pendingPaymentsCount", allPayments.stream().filter(p -> p.getPaymentStatus() == PaymentStatus.PENDING).count());
        model.addAttribute("paidPaymentsCount", allPayments.stream().filter(p -> p.getPaymentStatus() == PaymentStatus.PAID).count());

        // Bookings for manual recording dropdown
        List<Booking> bookings = bookingService.findAll();
        model.addAttribute("bookings", bookings);

        return "staff/payments";
    }

    @PostMapping("/record")
    public String recordManualPayment(@RequestParam("bookingId") Long bookingId,
                                      @RequestParam("paymentType") String paymentTypeStr,
                                      @RequestParam("amount") BigDecimal amount,
                                      @RequestParam(value = "paymentMethod", required = false) String paymentMethod,
                                      @RequestParam(value = "transactionReference", required = false) String transactionReference,
                                      @RequestParam(value = "paymentStatus", required = false, defaultValue = "PAID") String paymentStatusStr,
                                      @RequestParam(value = "remarks", required = false) String remarks,
                                      RedirectAttributes redirectAttributes) {
        try {
            PaymentType paymentType = PaymentType.valueOf(paymentTypeStr);
            PaymentStatus paymentStatus = PaymentStatus.valueOf(paymentStatusStr);

            paymentService.recordManualPayment(
                    bookingId,
                    paymentType,
                    amount,
                    paymentMethod,
                    transactionReference,
                    paymentStatus,
                    LocalDateTime.now(),
                    remarks
            );

            redirectAttributes.addFlashAttribute("successMessage", "Payment record successfully created and registered into system ledger.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to record payment: " + e.getMessage());
        }

        return "redirect:/staff/payments";
    }

    @PostMapping("/{id}/status")
    public String updatePaymentStatus(@PathVariable("id") Long id,
                                      @RequestParam("newStatus") String newStatusStr,
                                      @RequestParam(value = "transactionReference", required = false) String transactionReference,
                                      @RequestParam(value = "remarks", required = false) String remarks,
                                      RedirectAttributes redirectAttributes) {
        try {
            PaymentStatus newStatus = PaymentStatus.valueOf(newStatusStr);
            paymentService.updatePaymentStatus(id, newStatus, transactionReference, remarks);
            redirectAttributes.addFlashAttribute("successMessage", "Payment status updated to " + newStatus + " successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to update payment status: " + e.getMessage());
        }

        return "redirect:/staff/payments";
    }
}
