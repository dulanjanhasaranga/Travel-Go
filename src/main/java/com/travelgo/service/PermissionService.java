package com.travelgo.service;

import com.travelgo.entity.Permission;
import com.travelgo.repository.PermissionRepository;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

/**
 * Service for permission management operations.
 * Permissions are pre-seeded and managed by admin.
 */
@Service
public class PermissionService {

    private final PermissionRepository permissionRepository;

    public PermissionService(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    public List<Permission> getAllPermissions() {
        return permissionRepository.findAll();
    }

    public Optional<Permission> getPermissionById(Long id) {
        return permissionRepository.findById(id);
    }

    public List<Permission> getPermissionsByModule(String module) {
        return permissionRepository.findByModule(module);
    }

    public List<String> getAllModules() {
        return permissionRepository.findDistinctModules();
    }
}
