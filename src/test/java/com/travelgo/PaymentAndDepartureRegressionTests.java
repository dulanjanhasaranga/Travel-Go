package com.travelgo;

import com.travelgo.dto.BookingRequest;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Departure;
import com.travelgo.entity.Payment;
import com.travelgo.entity.VisaApplication;
import com.travelgo.enums.BookingStatus;
import com.travelgo.enums.PaymentStatus;
import com.travelgo.enums.PaymentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression coverage for state-changing payment and departure operations.
 * The parent fixture supplies an isolated H2 database and deterministic clock.
 */
class PaymentAndDepartureRegressionTests extends WorkflowIntegrationTests {

    @Test
    void assignedPermissionsCannotBypassTheAgreedBusinessRoleBoundaries() throws Exception {
        SecurityContextHolder.clearContext();
        var permissions = java.util.stream.Stream.of("PACKAGE_VIEW", "PACKAGE_MANAGE", "DESTINATION_VIEW", "DESTINATION_MANAGE",
                "BOOKING_VIEW", "BOOKING_MANAGE", "VISA_VIEW", "VISA_MANAGE", "PAYMENT_VIEW", "PAYMENT_MANAGE",
                "USER_VIEW", "USER_MANAGE", "STAFF_VIEW", "ROLE_VIEW", "PERMISSION_VIEW", "SYSTEM_SETTINGS_VIEW")
                .map(name -> new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_" + name)).toList();
        var adminAuthorities = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>(permissions);
        adminAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
        mvc.perform(get("/staff/dashboard").with(user(admin.getEmail()).authorities(adminAuthorities)))
                .andExpect(redirectedUrl("/admin/dashboard"));
        for (String path : List.of("/staff/packages", "/staff/destinations", "/staff/bookings", "/staff/inquiries", "/staff/visas", "/staff/payments")) {
            mvc.perform(get(path).with(user(admin.getEmail()).authorities(adminAuthorities))).andExpect(status().isForbidden());
        }
        var staffAuthorities = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>(permissions);
        staffAuthorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TRAVEL_CONSULTANT"));
        for (String path : List.of("/admin/users", "/admin/staff", "/admin/roles", "/admin/permissions", "/admin/settings", "/staff/visas", "/staff/payments")) {
            mvc.perform(get(path).with(user(consultant.getEmail()).authorities(staffAuthorities))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/staff/packages").with(user(consultant.getEmail()).authorities(staffAuthorities))).andExpect(status().isOk());
    }

    @Test
    void craftedCheckoutReturnParametersNeverCreatePayments() throws Exception {
        Booking booking = create(false);
        VisaApplication visa = quote(booking);
        String amount = visa.getTotalCharge().toPlainString();
        long before = paymentRepo.findByBooking_Id(booking.getId()).size();

        mvc.perform(get("/customer/payments/checkout/" + booking.getId())
                        .param("success", "true")
                        .param("simulated", "true")
                        .param("paymentType", PaymentType.VISA_DOCUMENTATION.name())
                        .param("amount", amount)
                        .with(user(customer.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isOk());
        assertEquals(before, paymentRepo.findByBooking_Id(booking.getId()).size());

        mvc.perform(get("/customer/payments/checkout/" + booking.getId())
                        .param("canceled", "true")
                        .param("simulated", "true")
                        .param("paymentType", PaymentType.VISA_DOCUMENTATION.name())
                        .param("amount", amount)
                        .with(user(customer.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isOk());
        assertEquals(before, paymentRepo.findByBooking_Id(booking.getId()).size());
    }

    @Test
    void protectedSimulatedPaymentUsesTheServerQuoteAndIsIdempotent() throws Exception {
        Booking booking = create(false);
        VisaApplication visa = quote(booking);
        String amount = visa.getTotalCharge().toPlainString();

        mvc.perform(post("/customer/payments/process")
                        .with(user(customer.getEmail()).roles("CUSTOMER"))
                        .with(csrf())
                        .param("bookingId", booking.getId().toString())
                        .param("paymentType", PaymentType.VISA_DOCUMENTATION.name())
                        .param("amount", amount))
                .andExpect(redirectedUrl("/customer/payments/checkout/" + booking.getId()))
                .andExpect(flash().attributeExists("successMessage"));

        List<Payment> paid = paymentRepo.findByBooking_Id(booking.getId());
        assertEquals(1, paid.size());
        assertEquals(PaymentStatus.PAID, paid.get(0).getPaymentStatus());
        assertEquals(0, new BigDecimal(amount).compareTo(paid.get(0).getAmount()));
        long notificationCount = notificationRepo.count();

        mvc.perform(post("/customer/payments/process")
                        .with(user(customer.getEmail()).roles("CUSTOMER"))
                        .with(csrf())
                        .param("bookingId", booking.getId().toString())
                        .param("paymentType", PaymentType.VISA_DOCUMENTATION.name())
                        .param("amount", amount))
                .andExpect(redirectedUrl("/customer/payments/checkout/" + booking.getId()))
                .andExpect(flash().attributeExists("successMessage"));

        assertEquals(1, paymentRepo.findByBooking_Id(booking.getId()).size());
        assertEquals(notificationCount, notificationRepo.count());
    }

    @Test
    void tamperedAndLegacyPaymentPostsCannotBypassWorkflowRules() throws Exception {
        Booking booking = create(false);
        VisaApplication visa = quote(booking);
        String correctVisaQuote = visa.getTotalCharge().toPlainString();

        mvc.perform(post("/customer/payments/process")
                        .with(user(customer.getEmail()).roles("CUSTOMER"))
                        .with(csrf())
                        .param("bookingId", booking.getId().toString())
                        .param("paymentType", PaymentType.VISA_DOCUMENTATION.name())
                        .param("amount", visa.getTotalCharge().add(BigDecimal.ONE).toPlainString()))
                .andExpect(redirectedUrl("/customer/payments/checkout/" + booking.getId()))
                .andExpect(flash().attributeExists("errorMessage"));
        assertEquals(0, paymentRepo.findByBooking_Id(booking.getId()).size());

        mvc.perform(post("/customer/payments/process")
                        .with(user(customer.getEmail()).roles("CUSTOMER"))
                        .param("bookingId", booking.getId().toString())
                        .param("paymentType", PaymentType.VISA_DOCUMENTATION.name())
                        .param("amount", correctVisaQuote))
                .andExpect(status().isForbidden());
        assertEquals(0, paymentRepo.findByBooking_Id(booking.getId()).size());

        mvc.perform(post("/customer/payments/process")
                        .with(user(customer.getEmail()).roles("CUSTOMER"))
                        .with(csrf())
                        .param("bookingId", booking.getId().toString())
                        .param("paymentType", PaymentType.PACKAGE_DEPOSIT.name())
                        .param("amount", payments.expectedAmount(booking, PaymentType.PACKAGE_DEPOSIT).toPlainString()))
                .andExpect(redirectedUrl("/customer/payments/checkout/" + booking.getId()))
                .andExpect(flash().attributeExists("errorMessage"));
        assertEquals(0, paymentRepo.findByBooking_Id(booking.getId()).size());
    }

    @Test
    void exportsAreAvailableOnlyToAdministrators() throws Exception {
        for (String endpoint : List.of("/staff/reports/bookings", "/staff/reports/payments", "/staff/reports/kpis")) {
            mvc.perform(get(endpoint).with(user(consultant.getEmail()).roles("TRAVEL_CONSULTANT")))
                    .andExpect(status().isForbidden());
            mvc.perform(get(endpoint).with(user(officer.getEmail()).roles("VISA_OFFICER")))
                    .andExpect(status().isForbidden());
            mvc.perform(get(endpoint).with(user(admin.getEmail()).roles("ADMIN")))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void departureInventoryReservesCapacityAndReleasesItOnCancellation() {
        Departure departure = departureOn(LocalDate.of(2030, 2, 1));
        assertEquals(10, departure.getAvailableSeats());

        Booking booking = create(false);
        assertEquals(9, departureOn(LocalDate.of(2030, 2, 1)).getAvailableSeats());

        login(customer);
        bookings.cancelBooking(booking.getId());
        assertEquals(BookingStatus.CANCELLED, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
        assertEquals(10, departureOn(LocalDate.of(2030, 2, 1)).getAvailableSeats());

        tx.executeWithoutResult(status -> {
            Departure constrained = departureRepo.findById(departure.getId()).orElseThrow();
            constrained.setAvailableSeats(1);
            departureRepo.saveAndFlush(constrained);
        });
        BookingRequest tooLarge = new BookingRequest(tour.getId(), LocalDate.of(2030, 2, 1), 2, null,
                List.of(traveler(), new BookingRequest.TravelerInput("Second traveler", "P234", LocalDate.of(1991, 2, 2), "Other", "Test")));
        login(customer);
        assertThrows(IllegalArgumentException.class, () -> bookings.createBooking(tooLarge));
        assertEquals(1, departureOn(LocalDate.of(2030, 2, 1)).getAvailableSeats());
    }

    @Test
    void movingABookingMovesItsSeatAllocationToTheOtherScheduledDeparture() {
        Booking booking = create(false);
        LocalDate firstDate = LocalDate.of(2030, 2, 1);
        LocalDate secondDate = LocalDate.of(2030, 3, 1);
        assertEquals(9, departureOn(firstDate).getAvailableSeats());
        assertEquals(10, departureOn(secondDate).getAvailableSeats());

        Booking current = bookingRepo.findById(booking.getId()).orElseThrow();
        login(customer);
        bookings.modifyBooking(booking.getId(), secondDate, null, null, current.getUpdatedAt());

        Booking moved = bookingRepo.findById(booking.getId()).orElseThrow();
        assertEquals(secondDate, moved.getTravelDate());
        assertEquals(10, departureOn(firstDate).getAvailableSeats());
        assertEquals(9, departureOn(secondDate).getAvailableSeats());
    }

    @Test
    void unreconciledHistoricalSeatInventoryBlocksFurtherReservations() {
        Booking original = create(false);
        Departure departure = departureOn(LocalDate.of(2030, 2, 1));
        tx.executeWithoutResult(status -> {
            Departure stored = departureRepo.findById(departure.getId()).orElseThrow();
            // Simulate a historical import that reset availability without removing the booking.
            stored.setAvailableSeats(stored.getTotalCapacity());
            departureRepo.saveAndFlush(stored);
        });

        login(other);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bookings.createBooking(bookingRequest("Historical mismatch", "P-HIST")));
        assertTrue(failure.getMessage().contains("Seat availability does not match existing bookings"));
        assertEquals(1, bookingsForDeparture(departure));
        assertEquals(1, bookingsForDepartureAndOwner(departure, customer.getId()));
        assertEquals(0, bookingsForDepartureAndOwner(departure, other.getId()));
        assertEquals(10, departureOn(LocalDate.of(2030, 2, 1)).getAvailableSeats());
        assertNotNull(bookingRepo.findById(original.getId()).orElseThrow());
    }

    @Test
    void failedHotelValidationRollsBackTheDepartureSeatReservation() {
        Departure departure = departureOn(LocalDate.of(2030, 2, 1));
        long bookingsBefore = bookingRepo.count();
        tx.executeWithoutResult(status -> {
            var inactiveHotel = hotelRepo.findById(hotel.getId()).orElseThrow();
            inactiveHotel.setActive(false);
            hotelRepo.saveAndFlush(inactiveHotel);
        });

        login(customer);
        assertThrows(IllegalArgumentException.class, () -> bookings.createBooking(
                new BookingRequest(tour.getId(), LocalDate.of(2030, 2, 1), 1, hotel.getId(), List.of(traveler()))));
        assertEquals(bookingsBefore, bookingRepo.count());
        assertEquals(10, departureOn(LocalDate.of(2030, 2, 1)).getAvailableSeats());
        assertEquals(10, departure.getAvailableSeats());
    }

    @Test
    void concurrentCustomersCompetingForOneWithheldSeatCreateExactlyOneBooking() throws Exception {
        Departure departure = departureOn(LocalDate.of(2030, 2, 1));
        tx.executeWithoutResult(status -> {
            Departure stored = departureRepo.findById(departure.getId()).orElseThrow();
            stored.setAvailableSeats(1);
            departureRepo.saveAndFlush(stored);
        });

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(competingBooking(customer, "First concurrent traveler", "P-RACE-1", ready, start));
            Future<Throwable> second = executor.submit(competingBooking(other, "Second concurrent traveler", "P-RACE-2", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            Throwable firstOutcome = first.get(15, TimeUnit.SECONDS);
            Throwable secondOutcome = second.get(15, TimeUnit.SECONDS);
            assertEquals(1, (firstOutcome == null ? 1 : 0) + (secondOutcome == null ? 1 : 0));
            Throwable capacityFailure = firstOutcome == null ? secondOutcome : firstOutcome;
            assertTrue(capacityFailure instanceof IllegalArgumentException);
            assertTrue(capacityFailure.getMessage().contains("Not enough seats remain"));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, bookingsForDeparture(departure));
        assertEquals(1, bookingsForDepartureAndOwner(departure, customer.getId())
                + bookingsForDepartureAndOwner(departure, other.getId()));
        assertEquals(0, departureOn(LocalDate.of(2030, 2, 1)).getAvailableSeats());
    }

    @Test
    void consultantCannotDeclineAReviewedAndPaidBooking() throws Exception {
        Booking booking = create(false);
        processing(booking);

        login(consultant);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bookings.declineBooking(booking.getId(), "Decline after visa review"));
        assertTrue(failure.getMessage().contains("only before visa review or payment begins"));
        assertEquals(BookingStatus.PENDING, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
    }

    @Test
    void repeatedConsultantConfirmAfterFullPaymentCreatesNoExtraNotification() throws Exception {
        Booking booking = create(false);
        VisaApplication visa = processing(booking);
        login(officer);
        visas.approveVisa(visa.getId());
        login(customer);
        pay(booking, PaymentType.FULL_PACKAGE, "100.00");
        long notificationsAfterPayment = notificationRepo.count();

        login(consultant);
        bookings.confirmBooking(booking.getId());
        bookings.confirmBooking(booking.getId());

        assertEquals(BookingStatus.CONFIRMED, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
        assertEquals(notificationsAfterPayment, notificationRepo.count());
    }

    private Callable<Throwable> competingBooking(com.travelgo.entity.User owner, String name, String passport,
                                                   CountDownLatch ready, CountDownLatch start) {
        return () -> {
            try {
                login(owner);
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                bookings.createBooking(bookingRequest(name, passport), "inventory-race-" + owner.getId());
                return null;
            } catch (Throwable failure) {
                return failure;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private BookingRequest bookingRequest(String travelerName, String passport) {
        return new BookingRequest(tour.getId(), LocalDate.of(2030, 2, 1), 1, null,
                List.of(new BookingRequest.TravelerInput(travelerName, passport, LocalDate.of(1990, 1, 1), "Other", "Test")));
    }

    private long bookingsForDeparture(Departure departure) {
        Long count = tx.execute(status -> bookingRepo.findAll().stream()
                .filter(booking -> booking.getDeparture() != null
                        && departure.getId().equals(booking.getDeparture().getId()))
                .count());
        return count == null ? 0 : count;
    }

    private long bookingsForDepartureAndOwner(Departure departure, Long ownerId) {
        Long count = tx.execute(status -> bookingRepo.findAll().stream()
                .filter(booking -> booking.getDeparture() != null
                        && departure.getId().equals(booking.getDeparture().getId()))
                .filter(booking -> ownerId.equals(booking.getUser().getId()))
                .count());
        return count == null ? 0 : count;
    }

    private Departure departureOn(LocalDate date) {
        return departureRepo.findByTourPackage_Id(tour.getId()).stream()
                .filter(departure -> date.equals(departure.getDepartureDate()))
                .findFirst()
                .orElseThrow();
    }
}
