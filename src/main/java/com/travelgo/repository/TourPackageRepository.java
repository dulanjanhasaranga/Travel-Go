package com.travelgo.repository;

import com.travelgo.entity.TourPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.data.domain.Pageable;

@Repository
public interface TourPackageRepository extends JpaRepository<TourPackage, Long> {
    String CATALOGUE_FILTERS = """
        WHERE p.isActive = true AND d.isActive = true
          AND (:destinationId IS NULL OR d.id = :destinationId)
          AND (:categoryId IS NULL OR c.id = :categoryId)
          AND (:query IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(p.includedServices) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(d.city) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(d.country) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%')))
          AND LOWER(CONCAT(d.city, ', ', d.country)) LIKE :destinationPattern ESCAPE '!'
          AND (:maxPrice IS NULL OR p.basePrice <= :maxPrice)
          AND (:minDays IS NULL OR p.durationDays >= :minDays)
          AND (:maxDays IS NULL OR p.durationDays <= :maxDays)
          AND (:travelers IS NULL OR p.maxCapacity >= :travelers)
          AND (:travelDate IS NULL OR EXISTS (
              SELECT departure.id FROM Departure departure
              WHERE departure.tourPackage = p
                AND departure.departureDate >= :travelDate
                AND departure.departureDate > :closedDepartureDate
                AND departure.returnDate >= departure.departureDate
                AND departure.availableSeats >= :departureTravelers
                AND departure.availableSeats <= departure.totalCapacity))
        """;

    @Query("SELECT COUNT(p) FROM TourPackage p JOIN p.destination d LEFT JOIN p.category c " + CATALOGUE_FILTERS)
    long countCatalogue(@Param("query") String query, @Param("destinationId") Long destinationId,
            @Param("categoryId") Long categoryId, @Param("destinationPattern") String destinationPattern,
            @Param("maxPrice") BigDecimal maxPrice, @Param("minDays") Integer minDays,
            @Param("maxDays") Integer maxDays, @Param("travelers") Integer travelers,
            @Param("travelDate") LocalDate travelDate, @Param("closedDepartureDate") LocalDate closedDepartureDate,
            @Param("departureTravelers") int departureTravelers);

    @Query("SELECT p FROM TourPackage p JOIN FETCH p.destination d LEFT JOIN FETCH p.category c " + CATALOGUE_FILTERS)
    List<TourPackage> findCatalogue(@Param("query") String query, @Param("destinationId") Long destinationId,
            @Param("categoryId") Long categoryId, @Param("destinationPattern") String destinationPattern,
            @Param("maxPrice") BigDecimal maxPrice, @Param("minDays") Integer minDays,
            @Param("maxDays") Integer maxDays, @Param("travelers") Integer travelers,
            @Param("travelDate") LocalDate travelDate, @Param("closedDepartureDate") LocalDate closedDepartureDate,
            @Param("departureTravelers") int departureTravelers, Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from TourPackage p where p.id = :id")
    java.util.Optional<TourPackage> findForUpdate(@Param("id") Long id);
    List<TourPackage> findByNameContainingIgnoreCase(String name);
    List<TourPackage> findByDestination_Id(Long destinationId);
    List<TourPackage> findByCategory_Id(Long categoryId);

    @Query("SELECT DISTINCT p FROM TourPackage p LEFT JOIN FETCH p.destination d LEFT JOIN FETCH p.category c WHERE " +
           "(:destinationId IS NULL OR d.id = :destinationId) AND " +
           "(:categoryId IS NULL OR c.id = :categoryId) AND " +
           "(:query IS NULL OR :query = '' OR " +
           "LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(p.includedServices) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(d.city) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(d.country) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<TourPackage> filterPackages(
            @Param("query") String query,
            @Param("destinationId") Long destinationId,
            @Param("categoryId") Long categoryId);
}
