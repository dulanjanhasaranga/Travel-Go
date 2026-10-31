package com.travelgo.config;

import com.travelgo.service.BookingHoldExpiryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Both explicit switches are needed; enabling the policy never sweeps historical unmarked holds. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "travelgo.bookings.pending-holds.enabled", havingValue = "true")
@ConditionalOnProperty(name = "travelgo.bookings.pending-holds.cleanup-enabled", havingValue = "true")
public class BookingHoldExpiryDispatcher {
    private final BookingHoldExpiryService expiry;
    public BookingHoldExpiryDispatcher(BookingHoldExpiryService expiry,
            @org.springframework.beans.factory.annotation.Value("${travelgo.bookings.pending-holds.poll-delay-ms:60000}") long pollDelay) {
        if (pollDelay < 1000 || pollDelay > 3_600_000)
            throw new IllegalArgumentException("Pending booking cleanup polling must be between one second and one hour.");
        this.expiry = expiry;
    }
    @Scheduled(fixedDelayString = "${travelgo.bookings.pending-holds.poll-delay-ms:60000}",
            initialDelayString = "${travelgo.bookings.pending-holds.poll-delay-ms:60000}")
    public void sweep() { expiry.sweep(); }
}
