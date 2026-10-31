package com.travelgo.repository;

import com.travelgo.entity.Departure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DepartureRepository extends JpaRepository<Departure, Long> {
    List<Departure> findByTourPackage_Id(Long tourPackageId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Departure d where d.id = :id")
    java.util.Optional<Departure> findByIdForUpdate(@Param("id") Long id);
}
