package com.travelgo.controller;

import com.travelgo.entity.*;
import com.travelgo.enums.BookingStatus;
import com.travelgo.enums.ContactMessageStatus;
import com.travelgo.enums.VisaStatus;
import com.travelgo.service.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for Staff Dashboard (Travel Consultant & Visa Officer).
 * Visa Officers receive an executive dashboard modeled after the Admin control center,
 * combining Visa Operations with Financial & Payment management.
 * Travel Consultants receive an executive Tour Package & Operations Command Center.
 */
@Controller
@RequestMapping("/staff")
public class StaffDashboardController {

    private final UserService userService;
    private final VisaApplicationService visaApplicationService;
    private final VisaDocumentService visaDocumentService;
    private final PaymentService paymentService;
    private final BookingService bookingService;
    private final TourPackageService tourPackageService;
    private final DestinationService destinationService;
    private final HotelService hotelService;
    private final PackageCategoryService categoryService;
    private final ContactMessageService contactMessageService;

    public StaffDashboardController(UserService userService,
                                    VisaApplicationService visaApplicationService,
                                    VisaDocumentService visaDocumentService,
                                    PaymentService paymentService,
                                    BookingService bookingService,
                                    TourPackageService tourPackageService,
                                    DestinationService destinationService,
                                    HotelService hotelService,
                                    PackageCategoryService categoryService,
                                    ContactMessageService contactMessageService) {
        this.userService = userService;
        this.visaApplicationService = visaApplicationService;
        this.visaDocumentService = visaDocumentService;
        this.paymentService = paymentService;
        this.bookingService = bookingService;
        this.tourPackageService = tourPackageService;
        this.destinationService = destinationService;
        this.hotelService = hotelService;
        this.categoryService = categoryService;
        this.contactMessageService = contactMessageService;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        if (userDetails != null) {
            userService.getUserByEmail(userDetails.getUsername()).ifPresent(user -> {
                model.addAttribute("currentUser", user);
                String roleName = user.getRole() != null ? com.travelgo.security.RoleNames.canonical(user.getRole().getRoleName()) : "";
                model.addAttribute("userRole", roleName);

                // Enrich model for Visa Officers
                if ("VISA_OFFICER".equals(roleName)) {
                    List<VisaApplication> allApps = visaApplicationService.findAll();
                    model.addAttribute("visaApplications", allApps);

                    // Visa Operations Counts
                    long totalCount = allApps.size();
                    long pendingCount = allApps.stream()
                            .filter(a -> a.getStatus() == VisaStatus.PENDING_DOCUMENTS || a.getStatus() == VisaStatus.DOCUMENTS_SUBMITTED)
                            .count();
                    long processingCount = allApps.stream()
                            .filter(a -> a.getStatus() == VisaStatus.PROCESSING || a.getStatus() == VisaStatus.DOCUMENTS_VERIFIED || a.getStatus() == VisaStatus.PAYMENT_PENDING)
                            .count();
                    long approvedCount = allApps.stream()
                            .filter(a -> a.getStatus() == VisaStatus.APPROVED)
                            .count();
                    long rejectedCount = allApps.stream()
                            .filter(a -> a.getStatus() == VisaStatus.REJECTED)
                            .count();

                    model.addAttribute("totalCount", totalCount);
                    model.addAttribute("pendingCount", pendingCount);
                    model.addAttribute("processingCount", processingCount);
                    model.addAttribute("approvedCount", approvedCount);
                    model.addAttribute("rejectedCount", rejectedCount);

                    // Recent 5 applications (newest first by ID)
                    List<VisaApplication> recent = allApps.stream()
                            .sorted(Comparator.comparing(VisaApplication::getId).reversed())
                            .limit(5)
                            .collect(Collectors.toList());
                    model.addAttribute("recentApplications", recent);

                    // Documents map for recent apps
                    Map<Long, List<com.travelgo.entity.VisaDocument>> documentsMap = new HashMap<>();
                    for (VisaApplication app : recent) {
                        documentsMap.put(app.getId(), visaDocumentService.findByVisaApplicationId(app.getId()));
                    }
                    model.addAttribute("documentsMap", documentsMap);

                    // Financial & Payment Metrics for Visa Officer
                    model.addAttribute("totalRevenue", paymentService.getTotalRevenue());
                    model.addAttribute("pendingReceivables", paymentService.getPendingReceivables());
                    model.addAttribute("visaPaymentsTotal", paymentService.getVisaPaymentsTotal());
                    model.addAttribute("packagePaymentsTotal", paymentService.getPackagePaymentsTotal());

                    // Recent payment transactions
                    List<Payment> recentPayments = paymentService.getRecentPayments(6);
                    model.addAttribute("recentPayments", recentPayments);

                    // Bookings list for quick offline payment recording modal
                    List<Booking> bookings = bookingService.findAll();
                    model.addAttribute("bookings", bookings);
                } else if ("TRAVEL_CONSULTANT".equals(roleName)) {
                    // Enrich model for Travel Consultant / Package Handling Manager
                    List<TourPackage> allPackages = tourPackageService.findAll();
                    List<Booking> allBookings = bookingService.findAll();
                    model.addAttribute("confirmableBookingIds", allBookings.stream().filter(bookingService::canConfirm).map(Booking::getId).toList());
                    List<Destination> allDestinations = destinationService.findAll();
                    List<Hotel> allHotels = hotelService.findAll();
                    List<PackageCategory> allCategories = categoryService.findAll();
                    List<ContactMessage> allInquiries = contactMessageService.findAll();

                    long totalPackages = allPackages.size();
                    long activePackages = allPackages.stream().filter(TourPackage::isActive).count();
                    long totalDestinations = allDestinations.size();
                    long totalHotels = allHotels.size();
                    long totalCategories = allCategories.size();
                    long totalBookings = allBookings.size();
                    long pendingBookings = allBookings.stream()
                            .filter(b -> b.getBookingStatus() == BookingStatus.PROCESSING || b.getBookingStatus() == BookingStatus.PENDING)
                            .count();
                    long totalInquiries = allInquiries.size();
                    long openInquiries = allInquiries.stream()
                            .filter(i -> i.getStatus() == ContactMessageStatus.OPEN)
                            .count();

                    model.addAttribute("totalPackages", totalPackages);
                    model.addAttribute("activePackages", activePackages);
                    model.addAttribute("totalDestinations", totalDestinations);
                    model.addAttribute("totalHotels", totalHotels);
                    model.addAttribute("totalCategories", totalCategories);
                    model.addAttribute("totalBookings", totalBookings);
                    model.addAttribute("pendingBookings", pendingBookings);
                    model.addAttribute("totalInquiries", totalInquiries);
                    model.addAttribute("openInquiries", openInquiries);

                    // Recent tour packages (newest first)
                    List<TourPackage> recentPackages = allPackages.stream()
                            .sorted(Comparator.comparing(TourPackage::getId).reversed())
                            .limit(6)
                            .collect(Collectors.toList());
                    model.addAttribute("recentPackages", recentPackages);

                    // Recent bookings (newest first)
                    List<Booking> recentBookings = allBookings.stream()
                            .sorted(Comparator.comparing(Booking::getId).reversed())
                            .limit(6)
                            .collect(Collectors.toList());
                    model.addAttribute("recentBookings", recentBookings);

                    // Collections needed for modals
                    model.addAttribute("destinations", allDestinations);
                    model.addAttribute("categories", allCategories);
                }
            });
        }
        return "staff/dashboard";
    }
}
