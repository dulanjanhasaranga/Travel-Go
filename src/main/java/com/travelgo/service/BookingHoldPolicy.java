package com.travelgo.service;

import com.travelgo.repository.BookingRepository;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Opt-in policy applies only to holds created with its explicit internal marker. */
@Component
public class BookingHoldPolicy {
    public static final String MANAGED_PREFIX = "AUTOHOLD-";
    private final boolean enabled;
    private final int minutes, maximumPending, batchSize;

    public BookingHoldPolicy(
            @Value("${travelgo.bookings.pending-holds.enabled:false}") boolean enabled,
            @Value("${travelgo.bookings.pending-holds.minutes:15}") int minutes,
            @Value("${travelgo.bookings.pending-holds.maximum-per-account:2}") int maximumPending,
            @Value("${travelgo.bookings.pending-holds.batch-size:25}") int batchSize) {
        if (minutes < 1 || minutes > 1440 || maximumPending < 1 || maximumPending > 20
                || batchSize < 1 || batchSize > 100)
            throw new IllegalArgumentException("Pending booking hold settings are outside the supported bounds.");
        this.enabled = enabled; this.minutes = minutes; this.maximumPending = maximumPending; this.batchSize = batchSize;
    }

    public static BookingHoldPolicy disabled() { return new BookingHoldPolicy(false, 15, 2, 25); }
    public boolean enabled() { return enabled; }
    public int minutes() { return minutes; }
    public int batchSize() { return batchSize; }
    public Duration duration() { return Duration.ofMinutes(minutes); }
    public String newReference() { return (enabled ? MANAGED_PREFIX : "") + UUID.randomUUID(); }
    public boolean manages(String reference) {
        return reference != null && reference.matches("AUTOHOLD-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    /** Caller holds the customer account lock; repeated request keys are resolved before this check. */
    public void requireAdmission(Long accountId, BookingRepository bookings) {
        if (enabled && bookings.countPendingManagedHolds(accountId, MANAGED_PREFIX + "%") >= maximumPending)
            throw new IllegalStateException("You already have " + maximumPending
                    + " pending booking requests. Continue or cancel an existing request before starting another.");
    }
}
