package com.travelgo;

import com.travelgo.controller.CustomerBookingController;
import com.travelgo.controller.PublicController;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Departure;
import com.travelgo.entity.Destination;
import com.travelgo.entity.TourPackage;
import com.travelgo.entity.User;
import com.travelgo.repository.DepartureRepository;
import com.travelgo.repository.DestinationRepository;
import com.travelgo.repository.PaymentRepository;
import com.travelgo.repository.SupplierHoldRepository;
import com.travelgo.repository.TourPackageRepository;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fixed-clock service and MVC checks use mocked repositories, never an application database. */
class DepartureAvailabilityTests {
    private final DepartureRepository departures = mock(DepartureRepository.class);
    private final TourPackageRepository packages = mock(TourPackageRepository.class);
    private DepartureAvailability availability;
    private TourPackage tour;
    private CatalogueDiscoveryService discovery;

    @BeforeEach
    void setUp() {
        availability = new DepartureAvailability(departures, clock("2030-01-10T00:00:00Z"));
        var destination = new Destination();
        destination.setId(1L);
        destination.setCity("Test destination");
        destination.setCountry("Test country");
        destination.setActive(true);
        tour = new TourPackage();
        tour.setId(1L);
        tour.setName("Test package");
        tour.setDestination(destination);
        tour.setBasePrice(new BigDecimal("100.00"));
        tour.setDurationDays(5);
        tour.setMaxCapacity(8);
        tour.setActive(true);
        when(packages.filterPackages(any(), any(), any())).thenReturn(List.of(tour));
        discovery = new CatalogueDiscoveryService(mock(DestinationRepository.class), packages, availability);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void expiredAndImminentDatesAreNotOfferedForBooking() {
        var past = departure(1L, "2030-01-09", 8, 8);
        var tomorrow = departure(2L, "2030-01-11", 8, 8);
        var deadlineToday = departure(3L, "2030-01-13", 8, 8);
        var eligible = departure(4L, "2030-01-14", 8, 8);
        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of(past, tomorrow, deadlineToday, eligible));

        assertEquals(List.of(eligible), availability.forPackage(1L, 1));
    }

    @Test
    void departureClosesExactlyAtTheThreeDayPaymentCutoff() {
        var scheduled = departure(1L, "2030-01-13", 8, 8);
        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of(scheduled));
        var beforeDeadline = new DepartureAvailability(departures, clock("2030-01-09T23:59:59Z"));
        var atDeadline = new DepartureAvailability(departures, clock("2030-01-10T00:00:00Z"));

        assertEquals(List.of(scheduled), beforeDeadline.forPackage(1L, 1));
        assertTrue(atDeadline.forPackage(1L, 1).isEmpty());
    }

    @Test
    void availabilityRequiresSeatsForTheWholeGroupAndKeepsDateOrder() {
        var tooSmall = departure(1L, "2030-01-14", 3, 8);
        var later = departure(2L, "2030-01-16", 4, 8);
        var first = departure(3L, "2030-01-15", 4, 8);
        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of(later, tooSmall, first));

        assertEquals(List.of(first, later), availability.forPackage(1L, 4));
    }

    @Test
    void incompleteOrInvalidSeatRecordsAreNotAdvertisedAsBookable() {
        var soldOut = departure(1L, "2030-01-14", 0, 8);
        var invalidSeats = departure(2L, "2030-01-14", 9, 8);
        var missingCapacity = departure(3L, "2030-01-14", 4, null);
        var missingSeats = departure(4L, "2030-01-14", null, 8);
        var missingDate = departure(5L, null, 4, 8);
        var eligible = departure(6L, "2030-01-14", 4, 8);
        var invalidReturn = departure(7L, "2030-01-14", 4, 8);
        invalidReturn.setReturnDate(LocalDate.of(2030, 1, 13));
        var missingReturn = departure(8L, "2030-01-14", 4, 8);
        missingReturn.setReturnDate(null);
        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of(soldOut, invalidSeats, missingCapacity, missingSeats, missingDate, invalidReturn, missingReturn, eligible));

        assertEquals(List.of(eligible), availability.forPackage(1L, 4));
        assertThrows(IllegalArgumentException.class, () -> availability.forPackage(1L, 0));
    }

    @Test
    void dateAndTravelerFiltersRejectDeparturesWithInsufficientSeats() {
        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of(departure(1L, "2030-01-14", 3, 8)));
        assertTrue(search(LocalDate.of(2030, 1, 14), 4).isEmpty());

        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of(departure(1L, "2030-01-14", 4, 8)));
        assertEquals(List.of(tour), search(LocalDate.of(2030, 1, 14), 4));
    }

    @Test
    void aPastStartDateFilterDoesNotReviveExpiredDepartures() {
        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of(departure(1L, "2030-01-13", 8, 8)));
        assertTrue(search(LocalDate.of(2029, 1, 1), null).isEmpty());
    }

    @Test
    void startDateMeansOnOrAfterAndUnfilteredCatalogueRemainsBrowseable() {
        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of(departure(1L, "2030-01-15", 8, 8)));
        assertEquals(List.of(tour), search(LocalDate.of(2030, 1, 14), null));
        assertTrue(search(LocalDate.of(2030, 1, 16), null).isEmpty());

        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of());
        assertEquals(List.of(tour), search(null, null));
    }

    @Test
    void publicPackageDetailsReceiveTheSameEligibleDepartureOptions() throws Exception {
        var packageService = mock(TourPackageService.class);
        when(packageService.findById(1L)).thenReturn(Optional.of(tour));
        var eligible = departure(2L, "2030-01-14", 8, 8);
        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of(departure(1L, "2030-01-13", 8, 8), eligible));
        var controller = new PublicController(pageLayout(), mock(DestinationService.class), packageService,
                mock(PackageCategoryService.class), mock(ContactMessageService.class), mock(UserService.class),
                mock(HotelService.class), discovery, availability);

        mvc(controller).perform(get("/packages/1"))
                .andExpect(status().isOk()).andExpect(view().name("package-detail"))
                .andExpect(model().attribute("departures", List.of(eligible)));
    }

    @Test
    void customerBookingFormReceivesOnlyEligibleDateOptions() throws Exception {
        var packageService = mock(TourPackageService.class);
        when(packageService.findById(1L)).thenReturn(Optional.of(tour));
        var eligible = departure(2L, "2030-01-14", 8, 8);
        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of(departure(1L, "2030-01-13", 8, 8), eligible));
        var controller = new PublicController(pageLayout(), mock(DestinationService.class), packageService,
                mock(PackageCategoryService.class), mock(ContactMessageService.class), mock(UserService.class),
                mock(HotelService.class), discovery, availability);
        login();

        mvc(controller).perform(get("/packages/1/book"))
                .andExpect(status().isOk()).andExpect(view().name("customer/booking-form"))
                .andExpect(model().attribute("departures", List.of(eligible)));
    }

    @Test
    void bookingModificationReceivesTheSameDeadlineFilteredDates() throws Exception {
        var owner = new User();
        owner.setId(1L);
        owner.setEmail("availability@example.invalid");
        var booking = new Booking();
        booking.setId(10L);
        booking.setUser(owner);
        booking.setTourPackage(tour);
        var bookingService = mock(BookingService.class);
        var userService = mock(UserService.class);
        when(bookingService.findById(10L)).thenReturn(Optional.of(booking));
        when(userService.getUserByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        var eligible = departure(2L, "2030-01-14", 8, 8);
        when(departures.findByTourPackage_Id(1L)).thenReturn(List.of(departure(1L, "2030-01-13", 8, 8), eligible));
        var controller = new CustomerBookingController(bookingService, mock(TourPackageService.class), userService,
                mock(HotelService.class), mock(BookingHotelService.class), mock(VisaApplicationService.class),
                mock(TravelerService.class), mock(ReviewService.class), availability,
                mock(SupplierHoldRepository.class), mock(PdfReceiptService.class), mock(PaymentRepository.class),
                mock(com.travelgo.service.TravelPolicyService.class));
        login();

        mvc(controller).perform(get("/customer/bookings/10"))
                .andExpect(status().isOk()).andExpect(view().name("customer/booking-detail"))
                .andExpect(model().attribute("departures", List.of(eligible)));
    }

    private RequestPageLayout pageLayout() {
        var layout = mock(RequestPageLayout.class);
        when(layout.get()).thenReturn(new PageLayoutService.Layout(new com.travelgo.entity.SystemSettings(), null));
        return layout;
    }

    private List<TourPackage> search(LocalDate date, Integer travelers) {
        return discovery.packages(null, null, null, null, null, null, null, travelers, date, "newest");
    }

    private Departure departure(Long id, String date, Integer seats, Integer capacity) {
        var departure = new Departure();
        departure.setId(id);
        departure.setTourPackage(tour);
        departure.setDepartureDate(date == null ? null : LocalDate.parse(date));
        departure.setReturnDate(date == null ? null : LocalDate.parse(date).plusDays(4));
        departure.setAvailableSeats(seats);
        departure.setTotalCapacity(capacity);
        return departure;
    }

    private Clock clock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private void login() {
        var principal = org.springframework.security.core.userdetails.User.withUsername("availability@example.invalid")
                .password("unused").roles("CUSTOMER").build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "unused", principal.getAuthorities()));
    }

    private MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }
}
