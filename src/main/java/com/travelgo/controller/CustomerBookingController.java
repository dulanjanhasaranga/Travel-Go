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
    private final com.travelgo.service.DepartureAvailability departureAvailability;
    private final com.travelgo.repository.SupplierHoldRepository supplierHoldRepository;
    private final com.travelgo.service.PdfReceiptService pdfReceiptService;
    private final com.travelgo.repository.PaymentRepository paymentRepository;
    private final com.travelgo.service.TravelPolicyService travelPolicyService;

    public CustomerBookingController(BookingService bookingService,
                                    TourPackageService tourPackageService,
                                    UserService userService,
                                    HotelService hotelService,
                                    BookingHotelService bookingHotelService,
                                    VisaApplicationService visaApplicationService,
                                    TravelerService travelerService, com.travelgo.service.ReviewService reviews,
                                    com.travelgo.service.DepartureAvailability departureAvailability,
                                    com.travelgo.repository.SupplierHoldRepository supplierHoldRepository,
                                    com.travelgo.service.PdfReceiptService pdfReceiptService,
                                    com.travelgo.repository.PaymentRepository paymentRepository,
                                    com.travelgo.service.TravelPolicyService travelPolicyService) {
        this.bookingService = bookingService;
        this.tourPackageService = tourPackageService;
        this.userService = userService;
        this.hotelService = hotelService;
        this.bookingHotelService = bookingHotelService;
        this.visaApplicationService = visaApplicationService;
        this.travelerService = travelerService;this.reviews=reviews;
        this.departureAvailability = departureAvailability;
        this.supplierHoldRepository = supplierHoldRepository;
        this.pdfReceiptService = pdfReceiptService;
        this.paymentRepository = paymentRepository;
        this.travelPolicyService = travelPolicyService;
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
            model.addAttribute("canDownloadReceipt", pdfReceiptService.hasReceiptablePayment(paymentRepository.findByBooking_Id(booking.getId())));
        model.addAttribute("canReview", reviews.canReview(booking));
        model.addAttribute("bookingReviews", reviews.findByBookingId(booking.getId()));
            model.addAttribute("canEditBooking", bookingService.canCustomerEdit(booking));
            model.addAttribute("canStartVisa", visaApplicationService.canStartVisa(booking));
            model.addAttribute("currentUser", user);
            model.addAttribute("supplierHold", supplierHoldRepository.findByBooking_Id(booking.getId()).orElse(null));
            model.addAttribute("departures", departureAvailability.forPackage(booking.getTourPackage().getId(), 1));
            bookingHotelService.findByBookingId(booking.getId())
                    .ifPresent(bh -> model.addAttribute("bookingHotel", bh));
            visaApplicationService.findByBookingId(booking.getId())
                    .ifPresent(va -> model.addAttribute("visaApplication", va));
            
            List<Traveler> travelers = travelerService.findByBookingId(booking.getId());
            model.addAttribute("travelers", travelers);
            model.addAttribute("bookingNotes", bookingService.findNotes(booking.getId()));
            model.addAttribute("bookingStatusHistory", bookingService.getStatusHistory(booking.getId()));
            
            return "customer/booking-detail";
        }).orElseGet(() -> {
            redirectAttributes.addFlashAttribute("errorMessage", "Booking not found.");
            return "redirect:/customer/bookings";
        });
    }

    @PostMapping("/create")
    public String createBooking(@RequestParam("requestToken") String requestToken, @RequestParam("packageId") Long packageId,
                               @RequestParam(value="policyAccepted",required=false) Boolean policyAccepted,
                               @RequestParam(value="policyId",required=false) Long policyId,
                               @RequestParam("travelDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate travelDate,
                               @RequestParam("numberOfTravelers") Integer numberOfTravelers,
                               @RequestParam(value = "hotelId", required = false) Long hotelId,
                               @RequestParam(value = "travelerNames", required = false) List<String> travelerNames,
                               @RequestParam(value = "travelerPassports", required = false) List<String> travelerPassports,
                               @RequestParam(value = "travelerDobs", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) List<LocalDate> travelerDobs,
                               @RequestParam(value = "travelerGenders", required = false) List<String> travelerGenders,
                               @RequestParam(value = "travelerNationalities", required = false) List<String> travelerNationalities,
                               @AuthenticationPrincipal UserDetails userDetails,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        try {
            bookingService.createBooking(new com.travelgo.dto.BookingRequest(packageId, travelDate, numberOfTravelers, hotelId,
                    com.travelgo.dto.BookingRequest.travelers(travelerNames, travelerPassports, travelerDobs, travelerGenders, travelerNationalities)), requestToken,policyAccepted,policyId);

            redirectAttributes.addFlashAttribute("successMessage", "Booking request saved. Add your visa documents to continue.");
        } catch (Exception e) {
            TourPackage pkg = tourPackageService.findById(packageId)
                    .filter(p -> p.isActive() && p.getDestination() != null && p.getDestination().isActive())
                    .orElse(null);
            if (pkg == null) {
                redirectAttributes.addFlashAttribute("errorMessage", "This tour package is unavailable. Choose another package or contact the agency.");
                return "redirect:/packages";
            }
            // Re-render this request directly: passport details must not enter a URL,
            // flash/session storage, or a newly persisted booking after a failed transaction.
            var availableDepartures = departureAvailability.forPackage(pkg.getId(), 1);
            var availableHotels = hotelService.findByDestinationId(pkg.getDestination().getId()).stream()
                    .filter(Hotel::isActive).toList();
            model.addAttribute("tourPackage", pkg);
            model.addAttribute("departures", availableDepartures);
            model.addAttribute("hotels", availableHotels);
            model.addAttribute("currentTravelPolicy", travelPolicyService.current().orElse(null));
            model.addAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
            // Keep a valid idempotency key: an uncertain response must not create a second booking.
            model.addAttribute("requestToken", requestToken != null && requestToken.matches("[a-zA-Z0-9-]{16,64}")
                    ? requestToken : java.util.UUID.randomUUID().toString());
            model.addAttribute("submittedTravelDate", travelDate);
            model.addAttribute("submittedTravelerCount", numberOfTravelers);
            model.addAttribute("submittedHotelId", hotelId);
            model.addAttribute("submittedDepartureAvailable", availableDepartures.stream()
                    .anyMatch(d -> java.util.Objects.equals(d.getDepartureDate(), travelDate)));
            model.addAttribute("submittedHotelAvailable", hotelId == null || availableHotels.stream()
                    .anyMatch(h -> h.getId().equals(hotelId)));
            model.addAttribute("submittedTravelers", travelerFormRows(numberOfTravelers,
                    travelerNames, travelerPassports, travelerDobs, travelerGenders, travelerNationalities));
            return "customer/booking-form";
        }
        return "redirect:/customer/bookings";
    }

    /** Preserve mismatched lists safely so the customer can complete the missing fields. */
    private List<TravelerFormRow> travelerFormRows(Integer count, List<String> names, List<String> passports,
            List<LocalDate> dobs, List<String> genders, List<String> nationalities) {
        int rows = Math.max(1, count == null ? 1 : count);
        for (List<?> values : java.util.Arrays.asList(names, passports, dobs, genders, nationalities)) {
            if (values != null) rows = Math.max(rows, values.size());
        }
        // Catalogue capacity is limited to 1,000; malformed counts must not allocate unbounded markup.
        return java.util.stream.IntStream.range(0, Math.min(rows, 1000))
                .mapToObj(i -> new TravelerFormRow(at(names, i), at(passports, i), at(dobs, i),
                        at(genders, i), at(nationalities, i))).toList();
    }

    private static <T> T at(List<T> values, int index) {
        return values != null && index < values.size() ? values.get(index) : null;
    }

    public record TravelerFormRow(String name, String passport, LocalDate dob, String gender, String nationality) {}

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
            redirectAttributes.addFlashAttribute("successMessage", "Booking details updated.");
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
            redirectAttributes.addFlashAttribute("successMessage", "Booking cancelled.");
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
            redirectAttributes.addFlashAttribute("successMessage", "Your reply has been sent to your travel consultant.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/customer/bookings/" + id;
    }

    @GetMapping("/{id}/receipt")
    public org.springframework.http.ResponseEntity<byte[]> downloadReceipt(@PathVariable("id") Long id,
                                                                           @AuthenticationPrincipal UserDetails userDetails) {
        User user = resolveUser(userDetails);
        if (user == null) return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();

        Booking booking = bookingService.findById(id).orElse(null);
        if (booking == null || !isOwner(booking, user)) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }

        List<com.travelgo.entity.Payment> payments = paymentRepository.findByBooking_Id(id);
        if (!pdfReceiptService.hasReceiptablePayment(payments)) {
            return receiptUnavailable(org.springframework.http.HttpStatus.CONFLICT,
                    "A payment receipt is available after a successful payment.");
        }
        byte[] pdfBytes;
        try {
            pdfBytes = pdfReceiptService.generateReceipt(booking, payments);
        } catch (IllegalStateException e) {
            return receiptUnavailable(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Your receipt is currently unavailable. Contact the travel team to review your payment records.");
        }

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
        headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment().filename("receipt-" + id + ".pdf").build());
        headers.setCacheControl("private, no-store");
        return new org.springframework.http.ResponseEntity<>(pdfBytes, headers, org.springframework.http.HttpStatus.OK);
    }

    private org.springframework.http.ResponseEntity<byte[]> receiptUnavailable(org.springframework.http.HttpStatus status, String message) {
        return org.springframework.http.ResponseEntity.status(status)
                .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
