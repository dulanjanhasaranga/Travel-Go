package com.travelgo.repository;
import com.travelgo.entity.OutboundEmail;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
public interface OutboundEmailRepository extends JpaRepository<OutboundEmail,Long> {
 boolean existsByEventKey(String key);
 Optional<OutboundEmail> findByEventKey(String key);
 List<OutboundEmail> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderById(String status,Instant now);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select e from OutboundEmail e where e.id=:id")
 Optional<OutboundEmail> lock(@Param("id") Long id);
 long countByStatus(String status);
}

