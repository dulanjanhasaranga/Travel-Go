package com.travelgo.service;

import com.travelgo.entity.AuditLog;
import com.travelgo.entity.User;
import com.travelgo.repository.AuditLogRepository;
import com.travelgo.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/** Administrative services validate the current account as well as the HTTP authorization. */
@Service
public class AdminAccess {
    private final UserRepository users;
    private final AuditLogRepository auditLogs;

    public AdminAccess(UserRepository users, AuditLogRepository auditLogs) {
        this.users = users;
        this.auditLogs = auditLogs;
    }

    public User requireAdministrator(String... permissions) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            throw new AccessDeniedException("Administrator access is required.");
        }
        User actor = users.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Administrator account is unavailable."));
        if (!actor.isActive() || actor.getRole() == null || !"ADMIN".equals(actor.getRole().getRoleName())) {
            throw new AccessDeniedException("An active administrator account is required.");
        }
        if (permissions.length > 0 && actor.getRole().getPermissions().stream()
                .noneMatch(permission -> Arrays.asList(permissions).contains(permission.getPermissionName()))) {
            throw new AccessDeniedException("Your administrator account does not have permission for this change.");
        }
        return actor;
    }

    public User requireMatchingAdministrator(String suppliedEmail, String... permissions) {
        User actor = requireAdministrator(permissions);
        if (suppliedEmail == null || !actor.getEmail().equalsIgnoreCase(suppliedEmail)) {
            throw new AccessDeniedException("The request must use your signed-in administrator account.");
        }
        return actor;
    }

    /** Joins the caller's transaction so a rolled-back change cannot leave a success audit record. */
    public void record(User actor, String action, String entityName, Long entityId, String details) {
        auditLogs.save(new AuditLog(action, entityName, entityId, actor.getEmail(), details));
    }
}
