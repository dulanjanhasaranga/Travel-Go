package com.travelgo.repository;

import com.travelgo.entity.Payment;
import com.travelgo.enums.PaymentStatus;
import com.travelgo.enums.PaymentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findFirstByBooking_IdAndPaymentTypeAndPaymentStatus(Long bookingId, PaymentType paymentType, PaymentStatus paymentStatus);
    List<Payment> findByBooking_Id(Long bookingId);
    List<Payment> findByBooking_IdOrderByCreatedAtDesc(Long bookingId);
    List<Payment> findAllByOrderByCreatedAtDesc();
    List<Payment> findByPaymentTypeOrderByCreatedAtDesc(PaymentType paymentType);
    List<Payment> findByPaymentStatusOrderByCreatedAtDesc(PaymentStatus paymentStatus);
}
