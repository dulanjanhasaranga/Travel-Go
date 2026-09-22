package com.travelgo.repository;

import com.travelgo.entity.VisaApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VisaApplicationRepository extends JpaRepository<VisaApplication, Long> {
    Optional<VisaApplication> findByBooking_Id(Long bookingId);
    List<VisaApplication> findByBooking_User_Id(Long userId);
}
