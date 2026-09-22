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

    public RoleService(RoleRepository roleRepository, PermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
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

    /**
     * Update the permissions assigned to a role.
     * @param roleId The role ID
     * @param permissionIds Set of permission IDs to assign
     */
    @Transactional
    public void updateRolePermissions(Long roleId, Set<Long> permissionIds) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found: " + roleId));

        Set<Permission> permissions = new HashSet<>(permissionRepository.findAllById(permissionIds));
        role.setPermissions(permissions);
        roleRepository.save(role);
    }

    /**
     * Get all staff-assignable roles (excludes CUSTOMER since customers self-register).
     */
    public List<Role> getStaffRoles() {
        return roleRepository.findAll().stream()
                .filter(r -> !"CUSTOMER".equals(r.getRoleName()))
                .toList();
    }
}
