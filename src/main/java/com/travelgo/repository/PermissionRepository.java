package com.travelgo.repository;

import com.travelgo.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Permission entity.
 */
@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByPermissionName(String permissionName);

    boolean existsByPermissionName(String permissionName);

    // Find permissions by module (e.g., "USER", "SYSTEM", "PACKAGE")
    List<Permission> findByModule(String module);

    // Get distinct module names for grouping in UI
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT p.module FROM Permission p ORDER BY p.module")
    List<String> findDistinctModules();
}
