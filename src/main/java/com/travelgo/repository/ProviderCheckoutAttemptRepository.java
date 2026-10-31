package com.travelgo.repository;
import com.travelgo.entity.ProviderCheckoutAttempt;
import com.travelgo.enums.PaymentType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface ProviderCheckoutAttemptRepository extends JpaRepository<ProviderCheckoutAttempt, Long> {
    boolean existsByBooking_Id(Long bookingId);
    List<ProviderCheckoutAttempt> findByBooking_IdAndPaymentTypeOrderByIdDesc(Long bookingId, PaymentType paymentType);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from ProviderCheckoutAttempt a where a.id = :id")
    Optional<ProviderCheckoutAttempt> lock(Long id);
}
