package com.travelgo.repository;

import com.travelgo.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    boolean existsByTourPackage_Id(Long packageId);
    java.util.Optional<Booking> findByRequestKey(String key);
    List<Booking> findByUser_IdOrderByCreatedAtDesc(Long userId);

    @org.springframework.data.jpa.repository.Query("SELECT SUM(b.numberOfTravelers) FROM Booking b WHERE b.tourPackage.id = :packageId AND b.travelDate = :travelDate AND b.bookingStatus IN ('PENDING', 'PROCESSING', 'CONFIRMED')")
    Integer sumTravelersForPackageAndDate(@org.springframework.data.repository.query.Param("packageId") Long packageId, @org.springframework.data.repository.query.Param("travelDate") java.time.LocalDate travelDate);

    @org.springframework.data.jpa.repository.Query("SELECT SUM(b.numberOfTravelers) FROM Booking b WHERE b.tourPackage.id = :packageId AND b.travelDate = :travelDate AND b.bookingStatus IN ('PENDING', 'PROCESSING', 'CONFIRMED') AND b.id <> :excludeId")
    Integer sumTravelersForPackageAndDateExcluding(@org.springframework.data.repository.query.Param("packageId") Long packageId, @org.springframework.data.repository.query.Param("travelDate") java.time.LocalDate travelDate, @org.springframework.data.repository.query.Param("excludeId") Long excludeId);
}
