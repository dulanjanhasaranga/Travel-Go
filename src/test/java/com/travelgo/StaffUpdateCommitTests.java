package com.travelgo;

import com.travelgo.service.StaffUpdatePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Commit/rollback boundaries are exercised without a database or a network broker. */
class StaffUpdateCommitTests {
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final StaffUpdatePublisher publisher = new StaffUpdatePublisher(messaging);
    private final CommitManager manager = new CommitManager();
    private final TransactionTemplate tx = new TransactionTemplate(manager);

    @Test void committedUpdateIsVisibleOnlyAfterCommit() {
        tx.executeWithoutResult(status -> {
            publisher.bookingChanged("BOOKING_UPDATED", 23L);
            verifyNoInteractions(messaging);
            assertFalse(manager.committed);
        });
        assertTrue(manager.committed);
        verify(messaging).convertAndSend("/topic/staff-updates", "{\"type\":\"BOOKING_UPDATED\",\"bookingId\":23}");
        verifyNoMoreInteractions(messaging);
    }

    @Test void rollbackDoesNotAnnounceAnUnsavedBooking() {
        tx.executeWithoutResult(status -> {
            publisher.bookingChanged("NEW_BOOKING", 24L);
            status.setRollbackOnly();
        });
        assertTrue(manager.rolledBack);
        assertFalse(manager.committed);
        verifyNoInteractions(messaging);
    }

    @Test void failedWorkflowDoesNotAnnounceItsEarlierUpdate() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            publisher.bookingChanged("BOOKING_CANCELLED", 25L);
            throw new IllegalStateException("Persistence failed.");
        }));
        assertTrue(manager.rolledBack);
        verifyNoInteractions(messaging);
    }

    @Test void unavailableLiveTransportDoesNotTurnACommittedBookingIntoAFailedSubmission() {
        doThrow(new IllegalStateException("Broker unavailable.")).when(messaging)
                .convertAndSend("/topic/staff-updates", "{\"type\":\"BOOKING_CONFIRMED\",\"bookingId\":26}");
        assertDoesNotThrow(() -> tx.executeWithoutResult(status -> publisher.bookingChanged("BOOKING_CONFIRMED", 26L)));
        assertTrue(manager.committed);
        assertFalse(manager.rolledBack);
    }

    @Test void updatesWithoutATransactionAndUnrecognizedEventsAreRejected() {
        assertThrows(IllegalStateException.class, () -> publisher.bookingChanged("NEW_BOOKING", 27L));
        tx.executeWithoutResult(status -> {
            assertThrows(IllegalArgumentException.class, () -> publisher.bookingChanged("UNKNOWN", 27L));
            assertThrows(IllegalArgumentException.class, () -> publisher.bookingChanged("NEW_BOOKING", 0L));
            assertThrows(IllegalArgumentException.class, () -> publisher.bookingChanged(null, 27L));
        });
        verifyNoInteractions(messaging);
    }

    private static class CommitManager extends AbstractPlatformTransactionManager {
        boolean committed;
        boolean rolledBack;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) { committed = true; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }
}
