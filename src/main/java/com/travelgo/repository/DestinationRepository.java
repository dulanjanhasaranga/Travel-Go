package com.travelgo.repository;

import com.travelgo.entity.Destination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DestinationRepository extends JpaRepository<Destination, Long> {
    List<Destination> findByCountryContainingIgnoreCaseOrCityContainingIgnoreCase(String country, String city);
}
