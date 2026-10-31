package com.travelgo.repository;

import com.travelgo.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Refund r where r.id=:id")
    Optional<Refund> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    Optional<Refund> findByPayment_Id(Long paymentId);
}
