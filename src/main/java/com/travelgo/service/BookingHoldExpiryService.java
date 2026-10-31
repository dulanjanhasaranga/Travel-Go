package com.travelgo.service;

import com.travelgo.entity.*;
import com.travelgo.enums.BookingStatus;
import com.travelgo.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Expires only new, untouched requests. All financial/customer/staff progress is preserved. */
@Service
public class BookingHoldExpiryService {
    private static final Logger log = LoggerFactory.getLogger(BookingHoldExpiryService.class);
    private final BookingHoldPolicy policy;
    private final BookingRepository bookings;
    private final SupplierHoldRepository holds;
    private final DepartureRepository departures;
    private final PaymentRepository payments;
    private final VisaApplicationRepository visas;
    private final ProviderCheckoutAttemptRepository checkouts;
    private final CancellationRequestRepository cancellations;
    private final BookingNoteRepository notes;
    private final BookingStatusHistoryRepository history;
    private final AuditLogRepository audits;
    private final NotificationRepository notifications;
    private final WorkflowRules rules;
    private final Clock clock;
    private final TransactionTemplate tx;
    // This cursor only makes bounded background scans fair; database locks govern every mutation.
    private LocalDateTime afterExpiry;
    private Long afterId = 0L;
    @PersistenceContext private EntityManager em;

    public BookingHoldExpiryService(BookingHoldPolicy policy, BookingRepository bookings, SupplierHoldRepository holds,
            DepartureRepository departures, PaymentRepository payments, VisaApplicationRepository visas,
            ProviderCheckoutAttemptRepository checkouts, CancellationRequestRepository cancellations,
            BookingNoteRepository notes, BookingStatusHistoryRepository history, AuditLogRepository audits,
            NotificationRepository notifications, WorkflowRules rules, Clock clock, PlatformTransactionManager manager) {
        this.policy = policy; this.bookings = bookings; this.holds = holds; this.departures = departures;
        this.payments = payments; this.visas = visas; this.checkouts = checkouts; this.cancellations = cancellations;
        this.notes = notes; this.history = history; this.audits = audits; this.notifications = notifications;
        this.rules = rules; this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(15);
    }

    public record SweepResult(int examined, int expired, int skipped, int failed) {}

    public synchronized SweepResult sweep() {
        if (!policy.enabled()) return new SweepResult(0, 0, 0, 0);
        var candidates = holds.findAbandonedManagedBookingIds(BookingHoldPolicy.MANAGED_PREFIX + "%",
                LocalDateTime.now(clock), afterExpiry, afterId, PageRequest.of(0, policy.batchSize()));
        if (candidates.size() == policy.batchSize()) {
            var last = candidates.get(candidates.size() - 1);
            afterExpiry = last.getExpiresAt(); afterId = last.getBookingId();
        } else { afterExpiry = null; afterId = 0L; }
        int expired = 0, skipped = 0, failed = 0;
        for (var candidate : candidates) {
            Long id = candidate.getBookingId();
            try { if (expireIfAbandoned(id)) expired++; else skipped++; }
            catch (RuntimeException failure) {
                failed++;
                log.warn("Pending booking expiry requires review for booking {} ({}).", id, failure.getClass().getSimpleName());
            }
        }
        return new SweepResult(candidates.size(), expired, skipped, failed);
    }

    /** Internal operation, not a customer/staff endpoint. The marker and all guards are rechecked under the booking lock. */
    public boolean expireIfAbandoned(Long id) {
        if (!policy.enabled() || id == null) return false;
        return Boolean.TRUE.equals(tx.execute(status -> expireLocked(id)));
    }

    private boolean expireLocked(Long id) {
        Booking booking = rules.lock(id);
        if (booking.getBookingStatus() != BookingStatus.PENDING) return false;
        SupplierHold hold = holds.findByBooking_Id(id).orElse(null);
        if (hold == null || !policy.manages(hold.getSupplierReference()) || hold.getHoldExpiresAt() == null
                || LocalDateTime.now(clock).isBefore(hold.getHoldExpiresAt()) || supplierWork(hold)) return false;
        // Even failed/pending payment attempts and an unsubmitted visa application require staff/customer review.
        if (payments.existsByBooking_Id(id) || visas.existsByBooking_Id(id) || checkouts.existsByBooking_Id(id)
                || cancellations.existsByBooking_Id(id) || notes.existsByBooking_Id(id)
                || history.existsByBooking_IdAndStatusNot(id, BookingStatus.PENDING)) return false;
        if (booking.getDeparture() == null || booking.getNumberOfTravelers() == null || booking.getNumberOfTravelers() < 1)
            throw new IllegalStateException("The booking allocation needs review before expiry.");
        Departure departure = departures.findByIdForUpdate(booking.getDeparture().getId())
                .orElseThrow(() -> new IllegalStateException("The booking departure needs review before expiry."));
        // A relation may already be managed. Reload the current locked row before using its counters.
        em.refresh(departure, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!departure.getTourPackage().getId().equals(booking.getTourPackage().getId())
                || !java.util.Objects.equals(departure.getDepartureDate(), booking.getTravelDate()))
            throw new IllegalStateException("The booking departure allocation needs review before expiry.");
        Integer available = departure.getAvailableSeats(), capacity = departure.getTotalCapacity();
        Integer allocated = bookings.sumTravelersForPackageAndDate(booking.getTourPackage().getId(), booking.getTravelDate());
        long released = (available == null ? -1L : available.longValue()) + booking.getNumberOfTravelers();
        if (available == null || capacity == null || available < 0 || available > capacity || released > capacity
                || allocated == null || allocated < booking.getNumberOfTravelers() || allocated != capacity - available)
            throw new IllegalStateException("Seat availability needs review before expiry.");
        departure.setAvailableSeats((int) released);
        booking.setBookingStatus(BookingStatus.EXPIRED);
        BookingStatusHistory event = new BookingStatusHistory(); event.setBooking(booking); event.setStatus(BookingStatus.EXPIRED);
        event.setChangedAt(LocalDateTime.now(clock));
        event.setRemarks("Unstarted booking request expired after its allocated hold. No visa, payment or staff progress was present.");
        history.save(event);
        AuditLog audit = new AuditLog("EXPIRE_PENDING_BOOKING", "Booking", id, "system:booking-hold-expiry",
                "New policy-managed, unstarted request expired; " + booking.getNumberOfTravelers() + " departure seats released. No financial records changed.");
        audit.setTimestamp(clock.instant()); audits.save(audit);
        Notification notification = new Notification(); notification.setUser(booking.getUser());
        notification.setType("BOOKING_EXPIRED"); notification.setTitle("Booking request expired");
        notification.setMessage("Booking #" + id + " expired because its visa application was not started within the reserved time. No payment was taken. You can make a new booking if seats remain.");
        notification.setRelatedEntityType("BOOKING"); notification.setRelatedEntityId(id); notification.setCreatedAt(LocalDateTime.now(clock));
        notifications.save(notification);
        bookings.saveAndFlush(booking);
        return true;
    }

    private boolean supplierWork(SupplierHold hold) {
        return hold.isConfirmed() || hold.getConfirmedAt() != null || hold.getConfirmedBy() != null
                || hold.getSupplierName() != null || hold.getConfirmationReference() != null || hold.getServiceNotes() != null;
    }
}
