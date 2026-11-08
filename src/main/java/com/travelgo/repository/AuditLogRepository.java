package com.travelgo.repository;

import com.travelgo.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    boolean existsByActionAndEntityNameAndEntityId(String action, String entityName, Long entityId);
    List<AuditLog> findByUsernameOrderByTimestampDesc(String username);
    List<AuditLog> findAllByOrderByTimestampDesc();
}
