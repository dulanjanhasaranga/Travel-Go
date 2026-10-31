package com.travelgo;

import com.travelgo.dto.BookingRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual row-lock/rollback checks use a guarded synthetic database and never send mail. */
@SpringBootTest(properties={"spring.config.import=", "travelgo.bookings.pending-holds.enabled=true",
    "travelgo.bookings.pending-holds.cleanup-enabled=false", "travelgo.bookings.pending-holds.minutes=15",
    "travelgo.bookings.pending-holds.maximum-per-account=2", "travelgo.bookings.pending-holds.batch-size=2",
    "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false",
    "travelgo.payhere.enabled=false", "travelgo.stripe.enabled=false", "travelgo.auth.google.enabled=false"})
@ActiveProfiles("test")
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:travelgo-booking-hold-expiry;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@Import({BookingHoldExpiryTests.Time.class, TestDatabaseSafetyConfiguration.class})
class BookingHoldExpiryTests {
    static class MutableClock extends Clock {
        volatile Instant instant = Instant.parse("2030-01-01T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        public Instant instant() { return instant; }
    }
    @TestConfiguration static class Time {
        @Bean @Primary MutableClock bookingHoldClock() { return new MutableClock(); }
    }
    @Autowired MutableClock clock;
    @Autowired BookingService bookings;
    @Autowired BookingHoldExpiryService expiry;
    @Autowired BookingHoldPolicy policy;
    @Autowired VisaApplicationService visaService;
    @Autowired CancellationRequestService cancellationService;
    @Autowired BookingRepository bookingRepo;
    @Autowired SupplierHoldRepository holds;
    @Autowired DepartureRepository departures;
    @Autowired PaymentRepository payments;
    @Autowired RefundRepository refunds;
    @Autowired VisaApplicationRepository visas;
    @Autowired ProviderCheckoutAttemptRepository checkouts;
    @Autowired BookingNoteRepository notes;
    @Autowired BookingStatusHistoryRepository histories;
    @Autowired NotificationRepository notifications;
    @Autowired AuditLogRepository audits;
    @Autowired TravelerRepository travelers;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired DestinationRepository destinations;
    @Autowired PackageCategoryRepository categories;
    @Autowired TourPackageRepository packages;
    @Autowired PlatformTransactionManager manager;
    TransactionTemplate tx;
    User customer;
    TourPackage tour;
    Departure departure, otherDeparture;
    final List<Long> fixtureBookingIds = new CopyOnWriteArrayList<>();
    final LocalDate travelDate = LocalDate.of(2030, 2, 1);

    @BeforeEach void fixture() {
        clock.instant = Instant.parse("2030-01-01T12:00:00Z");
        tx = new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(s -> {
            if (roles.findByRoleName("ADMIN").isEmpty()) roles.save(new Role("ADMIN", "Synthetic reference role"));
            customer = account();
            Destination destination = new Destination(); destination.setCity("Synthetic hold city"); destination.setCountry("Synthetic hold country"); destinations.save(destination);
            PackageCategory category = new PackageCategory(); category.setName("Synthetic hold " + UUID.randomUUID()); categories.save(category);
            tour = new TourPackage(); tour.setName("Synthetic hold tour"); tour.setDestination(destination); tour.setCategory(category);
            tour.setBasePrice(new BigDecimal("100.00")); tour.setDurationDays(4); tour.setMaxCapacity(10); packages.save(tour);
            departure = departure(travelDate); otherDeparture = departure(travelDate.plusMonths(1));
        });
        login(customer);
    }
    @AfterEach void isolateRemainingSyntheticHolds() {
        SecurityContextHolder.clearContext();
        // Other tests never sweep this test's intentionally retained financial/invalid fixtures.
        tx.executeWithoutResult(s -> fixtureBookingIds.forEach(id -> holds.findByBooking_Id(id)
                .ifPresent(h -> h.setSupplierReference("TEST-DONE-" + UUID.randomUUID()))));
        fixtureBookingIds.clear();
    }
    private User account() {
        Role role = roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.save(new Role("CUSTOMER", "Synthetic hold role")));
        return users.save(new User("Synthetic hold customer", UUID.randomUUID() + "@example.invalid", "unused-test-password", role));
    }
    private Departure departure(LocalDate date) {
        Departure value = new Departure(); value.setTourPackage(tour); value.setDepartureDate(date); value.setReturnDate(date.plusDays(4));
        value.setTotalCapacity(10); value.setAvailableSeats(10); return departures.save(value);
    }
    private void login(User user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getEmail(), "unused",
                WorkflowIntegrationTests.authorities("CUSTOMER")));
    }
    private BookingRequest request(LocalDate date, int count) {
        var data = java.util.stream.IntStream.range(0, count).mapToObj(i -> new BookingRequest.TravelerInput(
                "Synthetic traveler " + i, "TEST-PASSPORT-" + i, LocalDate.of(2000, 1, 1), "Other", "Synthetic")).toList();
        return new BookingRequest(tour.getId(), date, count, null, data);
    }
    private Booking create(String key) { return create(key, travelDate, 1); }
    private Booking create(String key, LocalDate date, int count) {
        Booking value = bookings.createBooking(request(date, count), key);
        fixtureBookingIds.add(value.getId()); return value;
    }
    private String key() { return UUID.randomUUID().toString(); }
    private void due() { clock.instant = Instant.parse("2030-01-01T12:15:00Z"); }
    private int seats(Departure d) { return departures.findById(d.getId()).orElseThrow().getAvailableSeats(); }
    private BookingStatus status(Booking b) { return bookingRepo.findById(b.getId()).orElseThrow().getBookingStatus(); }
    private void change(Booking b, Consumer<Booking> action) { tx.executeWithoutResult(s -> action.accept(bookingRepo.findById(b.getId()).orElseThrow())); }
    private long expiredEvents(Booking b) { return histories.findByBooking_IdOrderByChangedAtAsc(b.getId()).stream().filter(h -> h.getStatus() == BookingStatus.EXPIRED).count(); }
    private long expiryNotices(Booking b) { return notifications.findByUser_IdOrderByCreatedAtDesc(customer.getId()).stream()
            .filter(n -> "BOOKING_EXPIRED".equals(n.getType()) && b.getId().equals(n.getRelatedEntityId())).count(); }
    private long expiryAudits(Booking b) { return audits.findAll().stream().filter(a -> "EXPIRE_PENDING_BOOKING".equals(a.getAction()) && b.getId().equals(a.getEntityId())).count(); }

    @Test void expiresAtBoundaryOnceAndReplayingRequestNeverReservesAgain() {
        String key = key(); Booking booking = create(key, travelDate, 2);
        assertTrue(policy.manages(holds.findByBooking_Id(booking.getId()).orElseThrow().getSupplierReference()));
        assertEquals(8, seats(departure)); clock.instant = Instant.parse("2030-01-01T12:14:59Z");
        assertFalse(expiry.expireIfAbandoned(booking.getId())); due();
        assertTrue(expiry.expireIfAbandoned(booking.getId())); assertFalse(expiry.expireIfAbandoned(booking.getId()));
        assertEquals(BookingStatus.EXPIRED, status(booking)); assertEquals(10, seats(departure));
        assertEquals(1, expiredEvents(booking)); assertEquals(1, expiryNotices(booking)); assertEquals(1, expiryAudits(booking));
        assertEquals(2, travelers.findByBooking_Id(booking.getId()).size());
        assertEquals(0, new BigDecimal("200.00").compareTo(bookingRepo.findById(booking.getId()).orElseThrow().getTotalPackageAmount()));
        assertFalse(payments.existsByBooking_Id(booking.getId()));
        assertEquals(booking.getId(), bookings.createBooking(request(travelDate, 2), key).getId());
        assertEquals(10, seats(departure)); assertEquals(1, expiredEvents(booking));
        var event = histories.findByBooking_IdOrderByChangedAtAsc(booking.getId()).stream().filter(h -> h.getStatus() == BookingStatus.EXPIRED).findFirst().orElseThrow();
        assertNull(event.getChangedBy()); assertEquals(LocalDateTime.of(2030, 1, 1, 12, 15), event.getChangedAt());
    }

    @Test void historicalAndMalformedMarkersNeverEnterExpiry() {
        Booking historical = create(key()), malformed = create(key());
        tx.executeWithoutResult(s -> {
            holds.findByBooking_Id(historical.getId()).orElseThrow().setSupplierReference(UUID.randomUUID().toString());
            holds.findByBooking_Id(malformed.getId()).orElseThrow().setSupplierReference("AUTOHOLD-not-a-managed-reference");
        });
        due(); assertFalse(expiry.expireIfAbandoned(historical.getId())); assertFalse(expiry.expireIfAbandoned(malformed.getId()));
        assertEquals(BookingStatus.PENDING, status(historical)); assertEquals(BookingStatus.PENDING, status(malformed)); assertEquals(8, seats(departure));
    }

    @ParameterizedTest @EnumSource(value=BookingStatus.class, mode=EnumSource.Mode.EXCLUDE, names="PENDING")
    void progressingAndClosedBookingsNeverReleaseSeats(BookingStatus state) {
        Booking b = create(key()); change(b, value -> value.setBookingStatus(state)); due();
        assertFalse(expiry.expireIfAbandoned(b.getId())); assertEquals(state, status(b)); assertEquals(9, seats(departure));
        assertEquals(0, expiryAudits(b));
    }

    @ParameterizedTest @EnumSource(VisaStatus.class)
    void anyVisaApplicationProtectsCustomerProgress(VisaStatus state) {
        Booking b = create(key()); var visa = visaService.createVisaApplication(b, VisaType.values()[0]);
        tx.executeWithoutResult(s -> visas.findById(visa.getId()).orElseThrow().setStatus(state)); due();
        assertFalse(expiry.expireIfAbandoned(b.getId())); assertEquals(BookingStatus.PENDING, status(b)); assertEquals(9, seats(departure));
        assertEquals(state, visas.findById(visa.getId()).orElseThrow().getStatus());
    }

    @ParameterizedTest @EnumSource(PaymentStatus.class)
    void everyFinancialAttemptIncludingFailedAndRefundedIsPreserved(PaymentStatus state) {
        Booking b = create(key()); Payment payment = tx.execute(s -> {
            Payment value = new Payment(); value.setBooking(bookingRepo.findById(b.getId()).orElseThrow());
            value.setPaymentType(PaymentType.VISA_DOCUMENTATION); value.setPaymentMethod("Bank transfer"); value.setAmount(new BigDecimal("40.00"));
            value.setPaymentStatus(state); value.setTransactionReference("SYNTHETIC-" + UUID.randomUUID());
            if (state == PaymentStatus.PAID || state == PaymentStatus.REFUNDED) value.setPaidAt(LocalDateTime.now(clock));
            payments.saveAndFlush(value);
            if (state == PaymentStatus.REFUNDED) { Refund refund = new Refund(); refund.setPayment(value); refund.setAmount(BigDecimal.TEN);
                refund.setReason("Synthetic existing reimbursement"); refund.setStatus(RefundStatus.REFUND_PROCESSED); refund.setProcessedAt(LocalDateTime.now(clock)); refunds.save(refund); }
            return value;
        });
        due(); assertFalse(expiry.expireIfAbandoned(b.getId())); assertEquals(9, seats(departure)); assertEquals(BookingStatus.PENDING, status(b));
        Payment after = payments.findById(payment.getId()).orElseThrow(); assertEquals(state, after.getPaymentStatus());
        assertEquals(payment.getTransactionReference(), after.getTransactionReference()); assertEquals(0, new BigDecimal("40.00").compareTo(after.getAmount()));
    }

    @Test void hostedCheckoutWithoutPaymentPreventsExpiry() {
        Booking b = create(key()); tx.executeWithoutResult(s -> {
            var attempt = new ProviderCheckoutAttempt(); attempt.setBooking(bookingRepo.findById(b.getId()).orElseThrow());
            attempt.setPaymentType(PaymentType.VISA_DOCUMENTATION); attempt.setAmount(new BigDecimal("40.00"));
            attempt.setQuoteDeadline(LocalDateTime.now(clock).plusHours(1)); attempt.setExpiresAt(clock.instant().plusSeconds(3600).getEpochSecond());
            attempt.setPaymentMode("test"); attempt.setDeploymentReference("synthetic-no-provider"); attempt.setCreatedAt(clock.instant()); checkouts.save(attempt);
        });
        due(); assertFalse(expiry.expireIfAbandoned(b.getId())); assertEquals(9, seats(departure));
    }

    @Test void partialSupplierWorkIsNeverErased() {
        Booking b = create(key()); tx.executeWithoutResult(s -> holds.findByBooking_Id(b.getId()).orElseThrow().setSupplierName("Synthetic supplier under review"));
        due(); assertFalse(expiry.expireIfAbandoned(b.getId())); assertEquals(9, seats(departure));
    }

    @Test void notesAndHistoricalStaffProgressProtectReturnedPendingRequests() {
        Booking noted = create(key()), returned = create(key()); tx.executeWithoutResult(s -> {
            BookingNote note = new BookingNote(); note.setBooking(bookingRepo.findById(noted.getId()).orElseThrow()); note.setAuthor(customer);
            note.setNoteType("INTERNAL"); note.setMessage("Synthetic staff follow-up"); notes.save(note);
            BookingStatusHistory event = new BookingStatusHistory(); event.setBooking(bookingRepo.findById(returned.getId()).orElseThrow());
            event.setStatus(BookingStatus.INFO_REQUIRED); event.setRemarks("Synthetic prior staff request"); histories.save(event);
        });
        due(); assertFalse(expiry.expireIfAbandoned(noted.getId())); assertFalse(expiry.expireIfAbandoned(returned.getId())); assertEquals(8, seats(departure));
    }

    @Test void cancellationReviewProtectsItsPendingBooking() {
        Booking b = create(key()); cancellationService.request(b.getId(), "Synthetic cancellation review requested"); due();
        assertFalse(expiry.expireIfAbandoned(b.getId())); assertEquals(BookingStatus.PENDING, status(b)); assertEquals(9, seats(departure));
    }

    @Test void inconsistentInventoryRollsBackStatusAuditAndNotice() {
        Booking b = create(key()); tx.executeWithoutResult(s -> departures.findById(departure.getId()).orElseThrow().setAvailableSeats(10)); due();
        assertThrows(IllegalStateException.class, () -> expiry.expireIfAbandoned(b.getId()));
        assertEquals(BookingStatus.PENDING, status(b)); assertEquals(10, seats(departure));
        assertEquals(0, expiredEvents(b)); assertEquals(0, expiryAudits(b)); assertEquals(0, expiryNotices(b));
    }

    @Test void simultaneousExpiryReleasesInventoryOnlyOnce() throws Exception {
        Booking b = create(key()); due();
        var result = concurrent(() -> expiry.expireIfAbandoned(b.getId()), () -> expiry.expireIfAbandoned(b.getId()));
        assertEquals(1, result.stream().filter(Boolean.TRUE::equals).count()); assertEquals(10, seats(departure));
        assertEquals(1, expiredEvents(b)); assertEquals(1, expiryAudits(b)); assertEquals(1, expiryNotices(b));
    }

    @Test void cancellationAndExpiryCannotReleaseTheSameSeatsTwice() throws Exception {
        Booking b = create(key()); due();
        concurrent(() -> expiry.expireIfAbandoned(b.getId()), () -> {
            login(customer); try { bookings.cancelBooking(b.getId()); return true; }
            catch (IllegalStateException alreadyExpired) { return false; } finally { SecurityContextHolder.clearContext(); }
        });
        assertTrue(Set.of(BookingStatus.CANCELLED, BookingStatus.EXPIRED).contains(status(b))); assertEquals(10, seats(departure));
        assertEquals(status(b) == BookingStatus.EXPIRED ? 1 : 0, expiredEvents(b));
    }

    @Test void racingVisaStartPreservesWhicheverBookingLockWins() throws Exception {
        Booking b = create(key()); due();
        concurrent(() -> expiry.expireIfAbandoned(b.getId()), () -> {
            login(customer); try { visaService.createVisaApplication(b, VisaType.values()[0]); return true; }
            catch (IllegalStateException alreadyExpired) { return false; } finally { SecurityContextHolder.clearContext(); }
        });
        if (status(b) == BookingStatus.EXPIRED) { assertFalse(visas.existsByBooking_Id(b.getId())); assertEquals(10, seats(departure)); }
        else { assertEquals(BookingStatus.PENDING, status(b)); assertTrue(visas.existsByBooking_Id(b.getId())); assertEquals(9, seats(departure)); }
    }

    @Test void concurrentDifferentRequestsRespectAccountCapAndExactRetriesRemainAllowed() throws Exception {
        String firstKey = key(); Booking first = create(firstKey);
        var result = concurrent(() -> additionalRequest(), () -> additionalRequest());
        assertEquals(1, result.stream().filter(Boolean.TRUE::equals).count()); assertEquals(2, bookingRepo.findByUser_IdOrderByCreatedAtDesc(customer.getId()).size());
        assertEquals(8, seats(departure)); assertEquals(first.getId(), bookings.createBooking(request(travelDate, 1), firstKey).getId());
        due(); expiry.sweep(); expiry.sweep();
        assertEquals(10, seats(departure)); create(key()); assertEquals(9, seats(departure));
    }
    private boolean additionalRequest() {
        login(customer); try { create(key()); return true; }
        catch (IllegalStateException capReached) { assertTrue(capReached.getMessage().contains("pending booking requests")); return false; }
        finally { SecurityContextHolder.clearContext(); }
    }

    @Test void failedFirstBatchCannotStarveAnEligibleLaterDeparture() {
        Booking first = create(key()), second = create(key());
        User other = tx.execute(s -> account()); login(other); Booking third = create(key(), otherDeparture.getDepartureDate(), 1); login(customer);
        tx.executeWithoutResult(s -> departures.findById(departure.getId()).orElseThrow().setAvailableSeats(10)); due();
        // A cursor may remain from an earlier test; bounded repeated sweeps still reach every eligible fixture.
        boolean expired = false; int failures = 0;
        for (int i = 0; i < 5; i++) { var result = expiry.sweep(); failures += result.failed(); expired |= status(third) == BookingStatus.EXPIRED; }
        assertTrue(expired); assertTrue(failures >= 2); assertEquals(BookingStatus.PENDING, status(first)); assertEquals(BookingStatus.PENDING, status(second));
        assertEquals(10, seats(otherDeparture)); assertEquals(1, expiredEvents(third));
    }

    private <T> List<T> concurrent(Callable<T> first, Callable<T> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try {
            Callable<T> a = () -> { ready.countDown(); assertTrue(go.await(20, TimeUnit.SECONDS)); return first.call(); };
            Callable<T> b = () -> { ready.countDown(); assertTrue(go.await(20, TimeUnit.SECONDS)); return second.call(); };
            Future<T> fa = pool.submit(a), fb = pool.submit(b); assertTrue(ready.await(20, TimeUnit.SECONDS)); go.countDown();
            return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        } finally { go.countDown(); pool.shutdownNow(); }
    }
}
