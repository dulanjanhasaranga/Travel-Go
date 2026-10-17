package com.travelgo.repository;

import com.travelgo.entity.StaffMfaRecoveryCode;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffMfaRecoveryCodeRepository extends JpaRepository<StaffMfaRecoveryCode, Long> {
    List<StaffMfaRecoveryCode> findByUserIdAndConsumedAtIsNull(Long userId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from StaffMfaRecoveryCode c where c.userId=:userId and c.consumedAt is null order by c.id")
    List<StaffMfaRecoveryCode> lockAvailableByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
    /** Bulk DML is a current read under InnoDB; a derived select-then-delete can miss a concurrently replaced batch. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from StaffMfaRecoveryCode c where c.userId=:userId")
    void deleteByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
