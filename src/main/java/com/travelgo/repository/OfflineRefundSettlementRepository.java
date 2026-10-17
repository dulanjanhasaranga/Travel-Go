package com.travelgo.repository;

import com.travelgo.entity.OfflineRefundSettlement;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface OfflineRefundSettlementRepository extends JpaRepository<OfflineRefundSettlement, Long> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OfflineRefundSettlement s where s.refund.id=:refundId")
    Optional<OfflineRefundSettlement> lockByRefundId(@org.springframework.data.repository.query.Param("refundId") Long refundId);
    Optional<OfflineRefundSettlement> findByRefund_Id(Long refundId);
    boolean existsByChannelAndReference(String channel, String reference);
    @EntityGraph(attributePaths = {"refund", "refund.payment", "refund.payment.booking", "recordedBy"})
    List<OfflineRefundSettlement> findTop50ByOrderByRecordedAtDescIdDesc();
}
