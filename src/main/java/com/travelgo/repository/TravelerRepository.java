package com.travelgo.repository;

import com.travelgo.entity.Traveler;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TravelerRepository extends JpaRepository<Traveler, Long> {
    java.util.List<Traveler> findByBooking_Id(Long bookingId);
}
