package com.travelgo.repository;

import com.travelgo.entity.SupplierHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SupplierHoldRepository extends JpaRepository<SupplierHold, Long> {
    Optional<SupplierHold> findByBooking_Id(Long bookingId);
}
