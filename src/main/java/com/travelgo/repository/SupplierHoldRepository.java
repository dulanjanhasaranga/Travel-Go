package com.travelgo.repository;

import com.travelgo.entity.SupplierHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SupplierHoldRepository extends JpaRepository<SupplierHold, Long> {
    interface ExpiryCandidate {
        Long getBookingId();
        java.time.LocalDateTime getExpiresAt();
    }
    Optional<SupplierHold> findByBooking_Id(Long bookingId);
    @org.springframework.data.jpa.repository.Query("""
        SELECT h.booking.id AS bookingId, h.holdExpiresAt AS expiresAt FROM SupplierHold h WHERE h.supplierReference LIKE :prefix
        AND h.holdExpiresAt <= :now AND h.booking.bookingStatus = 'PENDING'
        AND (:afterExpiry IS NULL OR h.holdExpiresAt > :afterExpiry OR (h.holdExpiresAt = :afterExpiry AND h.booking.id > :afterId))
        AND h.isConfirmed = false AND h.confirmedAt IS NULL AND h.confirmedBy IS NULL
        AND h.supplierName IS NULL AND h.confirmationReference IS NULL AND h.serviceNotes IS NULL
        AND NOT EXISTS (SELECT p.id FROM Payment p WHERE p.booking = h.booking)
        AND NOT EXISTS (SELECT v.id FROM VisaApplication v WHERE v.booking = h.booking)
        AND NOT EXISTS (SELECT a.id FROM ProviderCheckoutAttempt a WHERE a.booking = h.booking)
        AND NOT EXISTS (SELECT c.id FROM CancellationRequest c WHERE c.booking = h.booking)
        AND NOT EXISTS (SELECT n.id FROM BookingNote n WHERE n.booking = h.booking)
        AND NOT EXISTS (SELECT s.id FROM BookingStatusHistory s WHERE s.booking = h.booking AND s.status <> 'PENDING')
        ORDER BY h.holdExpiresAt, h.booking.id
        """)
    java.util.List<ExpiryCandidate> findAbandonedManagedBookingIds(
            @org.springframework.data.repository.query.Param("prefix") String prefix,
            @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now,
            @org.springframework.data.repository.query.Param("afterExpiry") java.time.LocalDateTime afterExpiry,
            @org.springframework.data.repository.query.Param("afterId") Long afterId,
            org.springframework.data.domain.Pageable page);
}
