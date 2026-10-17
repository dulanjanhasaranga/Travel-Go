package com.travelgo.repository;
import com.travelgo.entity.ProviderRefundAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface ProviderRefundAttemptRepository extends JpaRepository<ProviderRefundAttempt,Long> {
    Optional<ProviderRefundAttempt> findByRefund_Id(Long refundId);
    boolean existsByProviderAndPaymentModeAndDeploymentReferenceAndProviderRefundReference(String provider,String mode,String deployment,String reference);
}
