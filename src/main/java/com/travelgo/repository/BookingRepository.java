package com.travelgo.repository;

import com.travelgo.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    boolean existsByTourPackage_Id(Long packageId);
    boolean existsByDeparture_Id(Long departureId);
    java.util.Optional<Booking> findByRequestKey(String key);
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(b) FROM Booking b, SupplierHold h WHERE h.booking = b AND b.user.id = :userId AND b.bookingStatus = 'PENDING' AND h.supplierReference LIKE :prefix")
    long countPendingManagedHolds(@org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("prefix") String prefix);
    List<Booking> findByUser_IdOrderByCreatedAtDesc(Long userId);

    // Completed trips retain their consumed historical seats; closeout never releases seats for resale.
    @org.springframework.data.jpa.repository.Query("SELECT SUM(b.numberOfTravelers) FROM Booking b WHERE b.tourPackage.id = :packageId AND b.travelDate = :travelDate AND b.bookingStatus IN ('PENDING', 'PROCESSING', 'INFO_REQUIRED', 'CONFIRMED', 'COMPLETED')")
    Integer sumTravelersForPackageAndDate(@org.springframework.data.repository.query.Param("packageId") Long packageId, @org.springframework.data.repository.query.Param("travelDate") java.time.LocalDate travelDate);

    @org.springframework.data.jpa.repository.Query("SELECT SUM(b.numberOfTravelers) FROM Booking b WHERE b.tourPackage.id = :packageId AND b.travelDate = :travelDate AND b.bookingStatus IN ('PENDING', 'PROCESSING', 'INFO_REQUIRED', 'CONFIRMED', 'COMPLETED') AND b.id <> :excludeId")
    Integer sumTravelersForPackageAndDateExcluding(@org.springframework.data.repository.query.Param("packageId") Long packageId, @org.springframework.data.repository.query.Param("travelDate") java.time.LocalDate travelDate, @org.springframework.data.repository.query.Param("excludeId") Long excludeId);
}
