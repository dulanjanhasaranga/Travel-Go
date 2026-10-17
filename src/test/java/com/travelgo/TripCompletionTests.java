package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolated closeout tests: fixtures never use configured application SQL or contact providers. */
@SpringBootTest(properties={"spring.config.import=", "spring.jpa.open-in-view=true",
    "travelgo.bookings.require-supplier-confirmation=true", "travelgo.mail.enabled=false",
    "travelgo.mail.dispatch-enabled=false", "travelgo.payments.simulated=true"})
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:travelgo-completion-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@Import({TripCompletionTests.Time.class, TestDatabaseSafetyConfiguration.class})
class TripCompletionTests {
    static class MutableClock extends Clock {
        volatile Instant instant = Instant.parse("2030-02-06T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        public Instant instant() { return instant; }
    }
    @TestConfiguration static class Time {
        @Bean @Primary MutableClock completionClock() { return new MutableClock(); }
    }
    @Autowired MutableClock clock;
    @Autowired BookingService bookings;
    @Autowired BookingSummaryService summaries;
    @Autowired BookingJourney journey;
    @Autowired ReviewService reviews;
    @Autowired PaymentService paymentService;
    @Autowired CancellationRequestService cancellationService;
    @Autowired DepartureAvailability departureAvailability;
    @MockitoSpyBean BookingEmailService email;
    @Autowired BookingRepository bookingRepo;
    @Autowired BookingStatusHistoryRepository histories;
    @Autowired AuditLogRepository audits;
    @Autowired NotificationRepository notifications;
    @Autowired OutboundEmailRepository emails;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired DestinationRepository destinations;
    @Autowired PackageCategoryRepository categories;
    @Autowired TourPackageRepository packages;
    @Autowired DepartureRepository departures;
    @Autowired VisaApplicationRepository visas;
    @Autowired PaymentRepository payments;
    @Autowired RefundRepository refunds;
    @Autowired SupplierHoldRepository suppliers;
    @Autowired CancellationRequestRepository cancellations;
    @Autowired PlatformTransactionManager manager;
    @Autowired WebApplicationContext context;
    TransactionTemplate tx;
    MockMvc mvc;
    User consultant, customer, officer, administrator;
    Booking booking;
    Long visaId, finalPaymentId, supplierId;

    @BeforeEach void fixture() {
        clock.instant = Instant.parse("2030-02-06T00:00:00Z");
        tx = new TransactionTemplate(manager);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tx.executeWithoutResult(s -> {
            consultant = account("TRAVEL_CONSULTANT"); customer = account("CUSTOMER");
            officer = account("VISA_OFFICER"); administrator = account("ADMIN");
            Role role = roles.findById(consultant.getRole().getId()).orElseThrow();
            var allowed = new HashSet<>(role.getPermissions());
            for (String name : List.of("BOOKING_VIEW", "BOOKING_MANAGE"))
                allowed.add(permissions.findByPermissionName(name).orElseGet(() -> permissions.save(new Permission(name, "Isolated closeout permission", "BOOKING"))));
            role.setPermissions(allowed); roles.save(role);
            Destination destination = new Destination(); destination.setCountry("Synthetic country"); destination.setCity("Synthetic city"); destinations.save(destination);
            PackageCategory category = new PackageCategory(); category.setName("Closeout " + UUID.randomUUID()); categories.save(category);
            TourPackage tour = new TourPackage(); tour.setName("Synthetic completed trip"); tour.setDestination(destination); tour.setCategory(category);
            tour.setBasePrice(new BigDecimal("100.00")); tour.setDurationDays(4); tour.setMaxCapacity(10); packages.save(tour);
            Departure departure = new Departure(); departure.setTourPackage(tour); departure.setDepartureDate(LocalDate.of(2030, 2, 1));
            departure.setReturnDate(LocalDate.of(2030, 2, 5)); departure.setTotalCapacity(10); departure.setAvailableSeats(9); departures.save(departure);
            booking = new Booking(); booking.setUser(customer); booking.setTourPackage(tour); booking.setDeparture(departure);
            booking.setTravelDate(departure.getDepartureDate()); booking.setNumberOfTravelers(1); booking.setPackageUnitPrice(new BigDecimal("100.00"));
            booking.setTotalPackageAmount(new BigDecimal("100.00")); booking.setBookingStatus(BookingStatus.CONFIRMED);
            booking.setPackagePaymentDeadline(LocalDateTime.of(2030, 1, 29, 0, 0)); bookingRepo.save(booking);
            VisaApplication visa = new VisaApplication(); visa.setBooking(booking); visa.setVisaType(VisaType.values()[0]);
            visa.setVisaCharge(new BigDecimal("30.00")); visa.setDocumentationCharge(new BigDecimal("10.00")); visa.setStatus(VisaStatus.APPROVED);
            visaId = visas.save(visa).getId();
            payment(PaymentType.VISA_DOCUMENTATION, "40.00"); finalPaymentId = payment(PaymentType.FULL_PACKAGE, "100.00").getId();
            SupplierHold supplier = new SupplierHold(); supplier.setBooking(booking); supplier.setSupplierReference("INTERNAL-" + UUID.randomUUID());
            supplier.setHoldExpiresAt(LocalDateTime.of(2030, 1, 1, 0, 0)); supplier.setConfirmed(true); supplier.setConfirmedAt(LocalDateTime.of(2030, 1, 1, 0, 0));
            supplier.setConfirmedBy(consultant); supplier.setSupplierName("Synthetic supplier"); supplier.setConfirmationReference("QA-" + UUID.randomUUID());
            supplier.setServiceNotes("Synthetic evidence only. No actual travel was arranged."); supplierId = suppliers.save(supplier).getId();
        });
        login(consultant);
    }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }
    private User account(String roleName) {
        Role role = roles.findByRoleName(roleName).orElseGet(() -> roles.save(new Role(roleName, "Isolated fixture")));
        return users.save(new User("Synthetic closeout user", UUID.randomUUID() + "@example.invalid", "unused-test-password", role));
    }
    private Payment payment(PaymentType type, String amount) {
        Payment payment = new Payment(); payment.setBooking(booking); payment.setPaymentType(type); payment.setAmount(new BigDecimal(amount));
        payment.setPaymentMethod("Simulation"); payment.setPaymentStatus(PaymentStatus.PAID); payment.setTransactionReference("QA-" + UUID.randomUUID());
        payment.setPaidAt(LocalDateTime.of(2030, 1, 1, 0, 0)); return payments.save(payment);
    }
    private void login(User actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor.getEmail(), "unused", WorkflowIntegrationTests.authorities(actor.getRole().getRoleName())));
    }
    private void change(Consumer<Booking> action) { tx.executeWithoutResult(s -> action.accept(bookingRepo.findById(booking.getId()).orElseThrow())); }
    private long noticeCount() { return notifications.findByUser_IdOrderByCreatedAtDesc(customer.getId()).stream().filter(n -> "BOOKING_COMPLETED".equals(n.getType())).count(); }
    private long auditCount() { return audits.findAll().stream().filter(a -> "COMPLETE_BOOKING".equals(a.getAction()) && booking.getId().equals(a.getEntityId())).count(); }
    private String emailKey() { return "bookingCompleted:" + booking.getId(); }

    @Test void returnDateBoundaryAndInvalidSchedulesCannotCompleteEarly() {
        clock.instant = Instant.parse("2030-02-05T23:59:59Z");
        assertThrows(IllegalStateException.class, () -> bookings.completeBooking(booking.getId()));
        assertFalse(Boolean.TRUE.equals(tx.execute(s -> bookings.canComplete(bookingRepo.findById(booking.getId()).orElseThrow()))));
        assertFalse(Boolean.TRUE.equals(tx.execute(s -> reviews.canReview(bookingRepo.findById(booking.getId()).orElseThrow()))));
        clock.instant = Instant.parse("2030-02-06T00:00:00Z");
        assertTrue(Boolean.TRUE.equals(tx.execute(s -> bookings.canComplete(bookingRepo.findById(booking.getId()).orElseThrow()))));
        assertTrue(Boolean.TRUE.equals(tx.execute(s -> reviews.canReview(bookingRepo.findById(booking.getId()).orElseThrow()))));
        change(b -> b.getDeparture().setReturnDate(LocalDate.of(2030, 1, 31)));
        assertThrows(IllegalStateException.class, () -> bookings.completeBooking(booking.getId()));
        assertEquals(BookingStatus.CONFIRMED, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
        assertEquals(0, noticeCount()); assertEquals(0, auditCount());
    }

    @Test void completionRechecksVisaPaymentsSupplierAndOpenCancellation() {
        change(b -> b.setBookingStatus(BookingStatus.PENDING));
        assertThrows(IllegalStateException.class, () -> bookings.completeBooking(booking.getId()));
        change(b -> b.setBookingStatus(BookingStatus.CONFIRMED));
        tx.executeWithoutResult(s -> visas.findById(visaId).orElseThrow().setStatus(VisaStatus.PROCESSING));
        assertThrows(IllegalStateException.class, () -> bookings.completeBooking(booking.getId()));
        tx.executeWithoutResult(s -> visas.findById(visaId).orElseThrow().setStatus(VisaStatus.APPROVED));
        tx.executeWithoutResult(s -> payments.findById(finalPaymentId).orElseThrow().setAmount(new BigDecimal("99.00")));
        assertThrows(IllegalStateException.class, () -> bookings.completeBooking(booking.getId()));
        tx.executeWithoutResult(s -> payments.findById(finalPaymentId).orElseThrow().setAmount(new BigDecimal("100.00")));
        tx.executeWithoutResult(s -> suppliers.findById(supplierId).orElseThrow().setConfirmed(false));
        assertThrows(IllegalStateException.class, () -> bookings.completeBooking(booking.getId()));
        tx.executeWithoutResult(s -> suppliers.findById(supplierId).orElseThrow().setConfirmed(true));
        tx.executeWithoutResult(s -> {
            CancellationRequest request = new CancellationRequest(); request.setBooking(bookingRepo.findById(booking.getId()).orElseThrow());
            request.setReason("Synthetic unresolved request"); request.setCreatedAt(LocalDateTime.of(2030, 1, 1, 0, 0)); cancellations.save(request);
        });
        assertThrows(IllegalStateException.class, () -> bookings.completeBooking(booking.getId()));
        assertEquals(0, noticeCount()); assertEquals(0, auditCount()); assertFalse(emails.existsByEventKey(emailKey()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(RefundStatus.class)
    void pendingOrProcessedPartialRefundRequiresReconciliationBeforeCompletion(RefundStatus status) {
        tx.executeWithoutResult(s -> {
            Refund refund = new Refund(); refund.setPayment(payments.findById(finalPaymentId).orElseThrow()); refund.setAmount(new BigDecimal("10.00"));
            refund.setStatus(status); refund.setReason("Synthetic reconciliation fixture"); refunds.save(refund);
        });
        assertThrows(IllegalStateException.class, () -> bookings.completeBooking(booking.getId()));
        assertEquals(BookingStatus.CONFIRMED, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
    }

    @Test void completionIsAuditedNotifiedAndIdempotentWithoutDemotingPaidBookings() {
        bookings.completeBooking(booking.getId()); bookings.completeBooking(booking.getId());
        assertEquals(BookingStatus.COMPLETED, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
        var history = histories.findByBooking_IdOrderByChangedAtAsc(booking.getId());
        assertEquals(1, history.size()); assertEquals(BookingStatus.COMPLETED, history.get(0).getStatus());
        assertEquals(consultant.getId(), history.get(0).getChangedBy().getId());
        assertEquals(1, auditCount()); assertEquals(1, noticeCount()); assertTrue(emails.existsByEventKey(emailKey()));
        assertThrows(IllegalStateException.class, () -> bookings.confirmBooking(booking.getId()));
        tx.executeWithoutResult(s -> {
            Booking current = bookingRepo.findById(booking.getId()).orElseThrow();
            assertEquals("Fully Paid", summaries.forBooking(current).status());
            assertTrue(journey.events(current).stream().anyMatch(event -> event.title().equals("Booking completed")));
            assertEquals(1, bookingRepo.sumTravelersForPackageAndDate(current.getTourPackage().getId(), current.getTravelDate()));
            assertEquals(9, current.getDeparture().getAvailableSeats());
            assertTrue(departureAvailability.forPackage(current.getTourPackage().getId(), 1).isEmpty());
        });
        login(customer); bookings.recordPaidConfirmation(booking.getId());
        Payment retry = new Payment(); retry.setBooking(booking); retry.setPaymentType(PaymentType.FULL_PACKAGE);
        retry.setAmount(new BigDecimal("100.00")); retry.setPaymentMethod("Simulation");
        assertEquals(finalPaymentId, paymentService.processPayment(retry).getId());
        assertEquals(2, payments.findByBooking_Id(booking.getId()).size());
        assertThrows(IllegalStateException.class, () -> cancellationService.request(booking.getId(), "Synthetic late cancellation"));
        assertEquals(BookingStatus.COMPLETED, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
        Review review = new Review(); review.setBooking(booking); review.setRating(4); review.setComment("Synthetic post-trip feedback"); reviews.save(review);
        Review duplicate = new Review(); duplicate.setBooking(booking); duplicate.setRating(5);
        assertThrows(IllegalArgumentException.class, () -> reviews.save(duplicate));
    }

    @Test void completionSideEffectsRollbackTogetherIfQueuingFails() {
        doThrow(new IllegalStateException("Synthetic queue failure")).when(email).completed(any(Booking.class));
        assertThrows(IllegalStateException.class, () -> bookings.completeBooking(booking.getId()));
        assertEquals(BookingStatus.CONFIRMED, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
        assertTrue(histories.findByBooking_IdOrderByChangedAtAsc(booking.getId()).isEmpty());
        assertEquals(0, noticeCount()); assertEquals(0, auditCount()); assertFalse(emails.existsByEventKey(emailKey()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=BookingStatus.class, names={"CANCELLED", "DECLINED", "EXPIRED"})
    void paidConfirmationCannotReopenTerminalBookings(BookingStatus status) {
        change(current -> current.setBookingStatus(status));
        long notificationCount = notifications.count(), auditCountBefore = audits.count(), emailCount = emails.count();
        long historyCount = histories.count();
        for (User actor : List.of(customer, officer)) {
            login(actor);
            assertThrows(IllegalStateException.class, () -> bookings.recordPaidConfirmation(booking.getId()));
            assertEquals(status, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
        }
        assertEquals(historyCount, histories.count()); assertEquals(notificationCount, notifications.count());
        assertEquals(auditCountBefore, audits.count()); assertEquals(emailCount, emails.count());
        assertEquals(2, payments.findByBooking_Id(booking.getId()).size());
    }

    @Test void completionRequiresCurrentConsultantPermissionAndCsrf() throws Exception {
        String path = "/staff/bookings/" + booking.getId() + "/complete";
        for (User actor : List.of(customer, officer, administrator)) {
            login(actor); assertThrows(AccessDeniedException.class, () -> bookings.completeBooking(booking.getId()));
            mvc.perform(post(path).with(WorkflowIntegrationTests.staffUser(actor.getEmail(), actor.getRole().getRoleName())).with(csrf())).andExpect(status().isForbidden());
        }
        mvc.perform(post(path).with(WorkflowIntegrationTests.staffUser(consultant.getEmail(), "TRAVEL_CONSULTANT"))).andExpect(status().isForbidden());
        tx.executeWithoutResult(s -> roles.findById(consultant.getRole().getId()).orElseThrow().getPermissions().clear());
        login(consultant); assertThrows(AccessDeniedException.class, () -> bookings.completeBooking(booking.getId()));
        mvc.perform(post(path).with(WorkflowIntegrationTests.staffUser(consultant.getEmail(), "TRAVEL_CONSULTANT")).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("errorMessage", "Access denied."));
        assertEquals(BookingStatus.CONFIRMED, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
    }

    @Test void mvcShowsCompletionDialogAndCustomerCompletedState() throws Exception {
        mvc.perform(get("/staff/bookings").with(WorkflowIntegrationTests.staffUser(consultant.getEmail(), "TRAVEL_CONSULTANT")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Mark trip complete?")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("action=\"/staff/bookings/" + booking.getId() + "/complete\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
        mvc.perform(post("/staff/bookings/" + booking.getId() + "/complete").with(WorkflowIntegrationTests.staffUser(consultant.getEmail(), "TRAVEL_CONSULTANT")).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("successMessage", "Trip marked complete."));
        mvc.perform(get("/customer/bookings/" + booking.getId()).with(WorkflowIntegrationTests.staffUser(customer.getEmail(), "CUSTOMER")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Your trip is complete")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Submit Trip Review")));
        mvc.perform(get("/customer/payments/checkout/" + booking.getId()).with(WorkflowIntegrationTests.staffUser(customer.getEmail(), "CUSTOMER")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Your trip is complete. No further payment is due.")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("If your quote has expired"))));
    }

    @Test void concurrentCloseoutProducesOnePersistedOutcome() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            List<Future<?>> attempts = new ArrayList<>();
            for (int i=0; i<2; i++) attempts.add(executor.submit(() -> {
                login(consultant); ready.countDown();
                try { if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic start timed out"); bookings.completeBooking(booking.getId()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            for (Future<?> attempt : attempts) attempt.get(20, TimeUnit.SECONDS);
            assertEquals(BookingStatus.COMPLETED, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
            assertEquals(1, histories.findByBooking_IdOrderByChangedAtAsc(booking.getId()).size()); assertEquals(1, auditCount()); assertEquals(1, noticeCount());
            assertTrue(emails.existsByEventKey(emailKey()));
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test void existingConfirmedTripsRemainReviewableWithoutStaffCloseout() {
        login(customer); Review review = new Review(); review.setBooking(booking); review.setRating(5); review.setComment("Synthetic legacy-compatible feedback");
        assertNotNull(reviews.save(review).getId());
        assertEquals(BookingStatus.CONFIRMED, bookingRepo.findById(booking.getId()).orElseThrow().getBookingStatus());
    }
}
