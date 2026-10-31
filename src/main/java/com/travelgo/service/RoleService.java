package com.travelgo.service;

import com.travelgo.entity.Permission;
import com.travelgo.entity.Role;
import com.travelgo.repository.PermissionRepository;
import com.travelgo.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Service for role management operations.
 * Handles role CRUD and role-permission assignments.
 */
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final AdminAccess adminAccess;

    public RoleService(RoleRepository roleRepository, PermissionRepository permissionRepository, AdminAccess adminAccess) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.adminAccess = adminAccess;
    }

    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }

    public Optional<Role> getRoleById(Long id) {
        return roleRepository.findById(id);
    }

    public Optional<Role> getRoleByName(String roleName) {
        return roleRepository.findByRoleName(roleName);
    }

    public boolean isConsultantRole(Role role) {
        return role != null && "TRAVEL_CONSULTANT".equals(com.travelgo.security.RoleNames.canonical(role.getRoleName()));
    }

    public List<String> missingConsultantBookingPermissions(Role role) {
        if (!isConsultantRole(role)) return List.of();
        var assigned = role.getPermissions().stream().map(Permission::getPermissionName).collect(java.util.stream.Collectors.toSet());
        return List.of("BOOKING_VIEW", "BOOKING_MANAGE").stream().filter(name -> !assigned.contains(name)).toList();
    }

    /** Adds only the two booking permissions, without overwriting concurrent unrelated grants. */
    @Transactional
    public boolean enableConsultantBookingAccess(Long roleId, boolean confirmed) {
        roleRepository.lockByRoleName("ADMIN").orElseThrow(() -> new IllegalStateException("Administrator role is unavailable."));
        var actor = adminAccess.requireAdministrator("ROLE_MANAGE");
        if (!confirmed) throw new IllegalArgumentException("Confirm booking access for all accounts assigned to this role.");
        var role = roleRepository.lockById(roleId).orElseThrow(() -> new IllegalArgumentException("Role not found."));
        if (!isConsultantRole(role)) throw new IllegalArgumentException("Booking access can be enabled here only for a travel consultant role.");
        var missing = missingConsultantBookingPermissions(role);
        if (missing.isEmpty()) return false;
        var grants = new HashSet<>(role.getPermissions());
        for (var name : missing) {
            grants.add(permissionRepository.findByPermissionName(name).orElseThrow(() ->
                    new IllegalStateException("A booking permission is unavailable. Ask the administrator to review the permission catalogue.")));
        }
        role.setPermissions(grants);
        roleRepository.save(role);
        adminAccess.record(actor, "ENABLE_BOOKING_ACCESS", "Role", roleId,
                "Added " + String.join(", ", missing) + " to " + role.getRoleName() + "; retained all other permissions.");
        return true;
    }

    /**
     * Update the permissions assigned to a role.
     * @param roleId The role ID
     * @param permissionIds Set of permission IDs to assign
     */
    @Transactional
    public void updateRolePermissions(Long roleId, Set<Long> permissionIds) {
        roleRepository.lockByRoleName("ADMIN").orElseThrow(() -> new IllegalStateException("Administrator role is unavailable."));
        var actor = adminAccess.requireAdministrator("ROLE_MANAGE");
        Role role = roleRepository.lockById(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found: " + roleId));

        Set<Long> requested = permissionIds == null ? Set.of() : permissionIds;
        if (requested.stream().anyMatch(id -> id == null || id <= 0)) throw new IllegalArgumentException("Choose valid permissions.");
        Set<Permission> permissions = new HashSet<>(permissionRepository.findAllById(requested));
        if (permissions.size() != requested.size()) throw new IllegalArgumentException("One or more selected permissions no longer exist. Reload this role before saving.");
        if ("ADMIN".equals(role.getRoleName()) && permissions.stream().noneMatch(permission -> "ROLE_MANAGE".equals(permission.getPermissionName()))) {
            throw new IllegalStateException("Administrators must retain Role management so access permissions can be restored.");
        }
        role.setPermissions(permissions);
        roleRepository.save(role);
        adminAccess.record(actor, "UPDATE_PERMISSIONS", "Role", roleId, "Updated permissions for role " + role.getRoleName() + ".");
    }

    /**
     * Get all staff-assignable roles (excludes CUSTOMER since customers self-register).
     */
    public List<Role> getStaffRoles() {
        return roleRepository.findAll().stream()
                .filter(r -> java.util.Set.of("ADMIN", "TRAVEL_CONSULTANT", "VISA_OFFICER").contains(com.travelgo.security.RoleNames.canonical(r.getRoleName())))
                .toList();
    }
}
