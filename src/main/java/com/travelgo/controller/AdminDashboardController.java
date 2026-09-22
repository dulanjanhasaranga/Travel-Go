package com.travelgo.controller;

import com.travelgo.service.SystemSettingsService;
import com.travelgo.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Controller for the Admin Dashboard.
 * Displays statistics and overview.
 */
@Controller
@RequestMapping("/admin")
public class AdminDashboardController {

    private final UserService userService;
    private final SystemSettingsService settingsService;
    private final com.travelgo.repository.BookingRepository bookings;
    private final com.travelgo.service.PaymentService payments;
    private final com.travelgo.service.BookingSummaryService summaries;
    private final com.travelgo.repository.ContactMessageRepository inquiries;
    private final com.travelgo.repository.OutboundEmailRepository emails;
    private final java.time.Clock clock;

    public AdminDashboardController(UserService userService, SystemSettingsService settingsService,com.travelgo.repository.BookingRepository bookings,com.travelgo.service.PaymentService payments,com.travelgo.service.BookingSummaryService summaries,com.travelgo.repository.ContactMessageRepository inquiries,com.travelgo.repository.OutboundEmailRepository emails,java.time.Clock clock) {
        this.userService = userService;
        this.settingsService = settingsService;this.bookings=bookings;this.payments=payments;this.summaries=summaries;this.inquiries=inquiries;this.emails=emails;this.clock=clock;
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        var all=bookings.findAll();
        model.addAttribute("totalBookings",all.size());model.addAttribute("totalCustomers",userService.getCustomers().size());
        model.addAttribute("upcomingBookings",all.stream().filter(b->b.getBookingStatus()==com.travelgo.enums.BookingStatus.CONFIRMED&&!b.getTravelDate().isBefore(java.time.LocalDate.now(clock))).count());
        model.addAttribute("fullyPaidBookings",all.stream().filter(b->summaries.forBooking(b).status().equals("Fully Paid")).count());
        model.addAttribute("revenue",payments.getTotalRevenue());model.addAttribute("pendingReceivables",payments.getPendingReceivables());
        model.addAttribute("newInquiries",inquiries.countByStatus(com.travelgo.enums.ContactMessageStatus.OPEN));
        model.addAttribute("openInquiries",inquiries.findAll().stream().filter(i->i.getStatus()!=com.travelgo.enums.ContactMessageStatus.RESOLVED&&i.getStatus()!=com.travelgo.enums.ContactMessageStatus.CLOSED).count());
        model.addAttribute("queuedEmails",emails.countByStatus("QUEUED"));model.addAttribute("reviewEmails",emails.countByStatus("REVIEW_REQUIRED")+emails.countByStatus("SENDING"));
        model.addAttribute("popularPackages",all.stream().filter(b->b.getBookingStatus()!=com.travelgo.enums.BookingStatus.CANCELLED).collect(java.util.stream.Collectors.groupingBy(b->b.getTourPackage().getName(),java.util.stream.Collectors.counting())).entrySet().stream().sorted(java.util.Map.Entry.<String,Long>comparingByValue().reversed()).limit(5).toList());
        model.addAttribute("totalUsers", userService.getTotalUsers());
        model.addAttribute("activeUsers", userService.getActiveUsers());
        model.addAttribute("inactiveUsers", userService.getInactiveUsers());
        model.addAttribute("totalStaff", userService.getTotalStaff());
        model.addAttribute("settings", settingsService.getSettings());
        return "admin/dashboard";
    }
}
