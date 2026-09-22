package com.travelgo.controller;

import com.travelgo.entity.User;
import com.travelgo.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Controller for Customer Dashboard.
 */
@Controller
@RequestMapping("/customer")
public class CustomerDashboardController {

    private final UserService userService;
    private final com.travelgo.repository.BookingRepository bookings;
    private final com.travelgo.repository.VisaApplicationRepository visas;
    private final com.travelgo.service.TourPackageService packages;
    private final java.time.Clock clock;

    public CustomerDashboardController(UserService userService, com.travelgo.repository.BookingRepository bookings,
        com.travelgo.repository.VisaApplicationRepository visas, com.travelgo.service.TourPackageService packages, java.time.Clock clock) {
        this.userService = userService;
        this.bookings=bookings;this.visas=visas;this.packages=packages;this.clock=clock;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        if (userDetails != null) {
            userService.getUserByEmail(userDetails.getUsername()).ifPresent(user -> {
                model.addAttribute("currentUser", user);
                var all=bookings.findByUser_IdOrderByCreatedAtDesc(user.getId());
                model.addAttribute("upcomingTrips",all.stream().filter(b->b.getBookingStatus()==com.travelgo.enums.BookingStatus.CONFIRMED&&!b.getTravelDate().isBefore(java.time.LocalDate.now(clock))).toList());
                model.addAttribute("recentBookings",all.stream().limit(5).toList());
                model.addAttribute("bookingCount",all.size());
                model.addAttribute("pendingCount",all.stream().filter(b->b.getBookingStatus()==com.travelgo.enums.BookingStatus.PENDING).count());
                model.addAttribute("confirmedCount",all.stream().filter(b->b.getBookingStatus()==com.travelgo.enums.BookingStatus.CONFIRMED).count());
                model.addAttribute("visaCount",visas.findByBooking_User_Id(user.getId()).size());
            });
        }
        model.addAttribute("suggestedPackages",packages.findAll().stream().filter(p->p.isActive()&&p.getDestination().isActive()).limit(3).toList());
        return "customer/dashboard";
    }
}
