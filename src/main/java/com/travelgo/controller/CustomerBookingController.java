package com.travelgo.controller;

import com.travelgo.entity.Booking;
import com.travelgo.entity.TourPackage;
import com.travelgo.entity.User;
import com.travelgo.entity.Hotel;
import com.travelgo.entity.BookingHotel;
import com.travelgo.entity.Traveler;
import com.travelgo.service.BookingService;
import com.travelgo.service.TourPackageService;
import com.travelgo.service.UserService;
import com.travelgo.service.HotelService;
import com.travelgo.service.BookingHotelService;
import com.travelgo.service.VisaApplicationService;
import com.travelgo.service.TravelerService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Controller for Customer Booking operations.
 * Enforces customer ownership on all booking actions.
 */
@Controller
@RequestMapping("/customer/bookings")
public class CustomerBookingController {

    private final BookingService bookingService;
    private final TourPackageService tourPackageService;
    private final UserService userService;
    private final HotelService hotelService;
    private final BookingHotelService bookingHotelService;
    private final VisaApplicationService visaApplicationService;
    private final TravelerService travelerService;
    private final com.travelgo.service.ReviewService reviews;
    private final com.travelgo.repository.DepartureRepository departureRepository;
    private final com.travelgo.repository.SupplierHoldRepository supplierHoldRepository;

    public CustomerBookingController(BookingService bookingService,
                                    TourPackageService tourPackageService,
                                    UserService userService,
                                    HotelService hotelService,
                                    BookingHotelService bookingHotelService,
                                    VisaApplicationService visaApplicationService,
                                    TravelerService travelerService, com.travelgo.service.ReviewService reviews,
                                    com.travelgo.repository.DepartureRepository departureRepository,
                                    com.travelgo.repository.SupplierHoldRepository supplierHoldRepository) {
        this.bookingService = bookingService;
        this.tourPackageService = tourPackageService;
        this.userService = userService;
        this.hotelService = hotelService;
        this.bookingHotelService = bookingHotelService;
        this.visaApplicationService = visaApplicationService;
        this.travelerService = travelerService;this.reviews=reviews;
        this.departureRepository = departureRepository;
        this.supplierHoldRepository = supplierHoldRepository;
    }

    /**
     * Resolve the authenticated user entity. Returns null if not found.
     */
    private User resolveUser(UserDetails userDetails) {
        if (userDetails == null) return null;
        return userService.getUserByEmail(userDetails.getUsername()).orElse(null);
    }

    /**
     * Verify booking ownership. Returns true if the booking belongs to the user.
     */
    private boolean isOwner(Booking booking, User user) {
        return user != null && booking.getUser().getId().equals(user.getId());
    }

    @GetMapping
    public String listBookings(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        List<Booking> customerBookings = bookingService.findByUserId(user.getId());
        model.addAttribute("bookings", customerBookings);
        model.addAttribute("editableBookingIds", customerBookings.stream().filter(bookingService::canCustomerEdit).map(Booking::getId).toList());
        model.addAttribute("currentUser", user);
        return "customer/bookings";
    }

    @GetMapping("/{id}")
    public String viewBooking(@PathVariable("id") Long id,
                             @AuthenticationPrincipal UserDetails userDetails,
                             Model model,
                             RedirectAttributes redirectAttributes) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        return bookingService.findById(id).map(booking -> {
            if (!isOwner(booking, user)) {
                redirectAttributes.addFlashAttribute("errorMessage", "Access denied.");
                return "redirect:/customer/bookings";
            }
            model.addAttribute("booking", booking);
        model.addAttribute("canReview", reviews.canReview(booking));
        model.addAttribute("bookingReviews", reviews.findByBookingId(booking.getId()));
            model.addAttribute("canEditBooking", bookingService.canCustomerEdit(booking));
            model.addAttribute("currentUser", user);
            model.addAttribute("supplierHold", supplierHoldRepository.findByBooking_Id(booking.getId()).orElse(null));
            model.addAttribute("departures", departureRepository.findByTourPackage_Id(booking.getTourPackage().getId()).stream().filter(d -> d.getAvailableSeats() > 0 && d.getDepartureDate().isAfter(java.time.LocalDate.now())).toList());
            bookingHotelService.findByBookingId(booking.getId())
                    .ifPresent(bh -> model.addAttribute("bookingHotel", bh));
            visaApplicationService.findByBookingId(booking.getId())
                    .ifPresent(va -> model.addAttribute("visaApplication", va));
            
            List<Traveler> travelers = travelerService.findByBookingId(booking.getId());
            model.addAttribute("travelers", travelers);
            model.addAttribute("bookingNotes", bookingService.findNotes(booking.getId()));
            
            return "customer/booking-detail";
        }).orElseGet(() -> {
            redirectAttributes.addFlashAttribute("errorMessage", "Booking not found.");
            return "redirect:/customer/bookings";
        });
    }

    @PostMapping("/create")
    public String createBooking(@RequestParam("requestToken") String requestToken, @RequestParam("packageId") Long packageId,
                               @RequestParam("travelDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate travelDate,
                               @RequestParam("numberOfTravelers") Integer numberOfTravelers,
                               @RequestParam(value = "hotelId", required = false) Long hotelId,
                               @RequestParam(value = "travelerNames", required = false) List<String> travelerNames,
                               @RequestParam(value = "travelerPassports", required = false) List<String> travelerPassports,
                               @RequestParam(value = "travelerDobs", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) List<LocalDate> travelerDobs,
                               @RequestParam(value = "travelerGenders", required = false) List<String> travelerGenders,
                               @RequestParam(value = "travelerNationalities", required = false) List<String> travelerNationalities,
                               @AuthenticationPrincipal UserDetails userDetails,
                               RedirectAttributes redirectAttributes) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        try {
            bookingService.createBooking(new com.travelgo.dto.BookingRequest(packageId, travelDate, numberOfTravelers, hotelId,
                    com.travelgo.dto.BookingRequest.travelers(travelerNames, travelerPassports, travelerDobs, travelerGenders, travelerNationalities)), requestToken);

            redirectAttributes.addFlashAttribute("successMessage", "Booking created successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/customer/bookings";
    }

    @PostMapping("/{id}/modify")
    public String modifyBooking(@PathVariable("id") Long id,
                               @RequestParam(value = "travelDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate travelDate,
                               @RequestParam(value = "numberOfTravelers", required = false) Integer numberOfTravelers,
                               @RequestParam("expectedUpdatedAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime expectedUpdatedAt,
                               @RequestParam(value="travelerNames", required=false) List<String> travelerNames,
                               @RequestParam(value="travelerPassports", required=false) List<String> travelerPassports,
                               @RequestParam(value="travelerDobs", required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) List<LocalDate> travelerDobs,
                               @RequestParam(value="travelerGenders", required=false) List<String> travelerGenders,
                               @RequestParam(value="travelerNationalities", required=false) List<String> travelerNationalities,
                               @AuthenticationPrincipal UserDetails userDetails,
                               RedirectAttributes redirectAttributes) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        try {
            Booking booking = bookingService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Booking not found"));

            if (!isOwner(booking, user)) {
                redirectAttributes.addFlashAttribute("errorMessage", "Access denied.");
                return "redirect:/customer/bookings";
            }

            bookingService.modifyBooking(id, travelDate, numberOfTravelers,
                    travelerNames == null ? null : com.travelgo.dto.BookingRequest.travelers(travelerNames, travelerPassports, travelerDobs, travelerGenders, travelerNationalities), expectedUpdatedAt);
            redirectAttributes.addFlashAttribute("successMessage", "Booking modified successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/customer/bookings/" + id;
    }

    @PostMapping("/{id}/cancel")
    public String cancelBooking(@PathVariable("id") Long id,
                               @AuthenticationPrincipal UserDetails userDetails,
                               RedirectAttributes redirectAttributes) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        try {
            Booking booking = bookingService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Booking not found"));

            if (!isOwner(booking, user)) {
                redirectAttributes.addFlashAttribute("errorMessage", "Access denied.");
                return "redirect:/customer/bookings";
            }

            bookingService.cancelBooking(id);
            redirectAttributes.addFlashAttribute("successMessage", "Booking cancelled successfully.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/customer/bookings";
    }

    @PostMapping("/{id}/respond")
    public String respondToInfoRequest(@PathVariable("id") Long id,
                                       @RequestParam("message") String message,
                                       @AuthenticationPrincipal UserDetails userDetails,
                                       RedirectAttributes redirectAttributes) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        try {
            Booking booking = bookingService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Booking not found"));

            if (!isOwner(booking, user)) {
                redirectAttributes.addFlashAttribute("errorMessage", "Access denied.");
                return "redirect:/customer/bookings";
            }

            bookingService.respondToInfoRequest(id, message);
            redirectAttributes.addFlashAttribute("successMessage", "Your response has been sent to the travel consultant.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/customer/bookings/" + id;
    }
}
