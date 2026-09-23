package com.travelgo.service;

import com.travelgo.entity.AuditLog;
import com.travelgo.repository.AuditLogRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Logs an action with propagation REQUIRES_NEW so that audit logs are
     * committed even if the outer transaction rolls back (e.g. tracking failed attempts).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(String action, String entityName, Long entityId, String details) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = "system";
        if (auth != null && auth.isAuthenticated() && !auth.getPrincipal().equals("anonymousUser")) {
            username = auth.getName();
        }

        AuditLog log = new AuditLog(action, entityName, entityId, username, details);
        auditLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getAllLogs() {
        return auditLogRepository.findAllByOrderByTimestampDesc();
    }
}
