package com.travelgo.repository;

import com.travelgo.entity.Departure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DepartureRepository extends JpaRepository<Departure, Long> {
    List<Departure> findByTourPackage_Id(Long tourPackageId);
}
