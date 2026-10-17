package com.travelgo.repository;

import com.travelgo.entity.ProviderPaymentReview;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface ProviderPaymentReviewRepository extends JpaRepository<ProviderPaymentReview, Long> {
    boolean existsByEventReference(String eventReference);
    List<ProviderPaymentReview> findByStatusOrderByCreatedAtDesc(String status);
    long countByStatus(String status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProviderPaymentReview r where r.id = :id")
    Optional<ProviderPaymentReview> lock(Long id);
}
