package com.travelgo.repository;

import com.travelgo.entity.TourPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TourPackageRepository extends JpaRepository<TourPackage, Long> {
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
