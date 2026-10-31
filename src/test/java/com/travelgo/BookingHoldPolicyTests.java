package com.travelgo;

import com.travelgo.repository.BookingRepository;
import com.travelgo.service.BookingHoldPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingHoldPolicyTests {
    @Test void disabledPolicyDoesNotInspectOrManageHistoricalBookings() {
        var repository = mock(BookingRepository.class); var policy = BookingHoldPolicy.disabled();
        policy.requireAdmission(1L, repository); verifyNoInteractions(repository);
        assertFalse(policy.manages(policy.newReference())); assertFalse(policy.enabled());
    }
    @Test void enabledMarkersAndBoundsAreExplicit() {
        var policy = new BookingHoldPolicy(true, 15, 2, 25);
        assertTrue(policy.manages(policy.newReference())); assertFalse(policy.manages("AUTOHOLD-something"));
        assertThrows(IllegalArgumentException.class, () -> new BookingHoldPolicy(true, 0, 2, 25));
        assertThrows(IllegalArgumentException.class, () -> new BookingHoldPolicy(true, 1441, 2, 25));
        assertThrows(IllegalArgumentException.class, () -> new BookingHoldPolicy(true, 15, 0, 25));
        assertThrows(IllegalArgumentException.class, () -> new BookingHoldPolicy(true, 15, 21, 25));
        assertThrows(IllegalArgumentException.class, () -> new BookingHoldPolicy(true, 15, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new BookingHoldPolicy(true, 15, 2, 101));
    }
}
