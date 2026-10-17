package com.travelgo.service;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A live hint is sent only after the booking and its persistent notifications commit. */
@Service
public class StaffUpdatePublisher {
    private static final Logger log = LoggerFactory.getLogger(StaffUpdatePublisher.class);
    private static final Set<String> EVENTS = Set.of("NEW_BOOKING", "BOOKING_CONFIRMED", "BOOKING_COMPLETED", "BOOKING_UPDATED",
            "BOOKING_CANCELLED", "BOOKING_DECLINED", "BOOKING_INFO_REQUESTED", "BOOKING_INFO_REPLIED");
    private final SimpMessagingTemplate messaging;

    public StaffUpdatePublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void bookingChanged(String eventType, Long bookingId) {
        if (eventType == null || !EVENTS.contains(eventType) || bookingId == null || bookingId < 1) {
            throw new IllegalArgumentException("A valid booking update is required.");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Booking updates require an active transaction.");
        }
        String payload = "{\"type\":\"" + eventType + "\",\"bookingId\":" + bookingId + "}";
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    messaging.convertAndSend("/topic/staff-updates", payload);
                } catch (RuntimeException unavailable) {
                    // The committed inbox remains authoritative if the optional live transport is unavailable.
                    log.warn("Live booking update unavailable for booking {}", bookingId);
                }
            }
        });
    }
}
