package com.travelgo.repository;

import com.travelgo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Repository for User entity.
 * Provides CRUD + custom queries for user management.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id=:id")
    Optional<User> lockAccount(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"role", "role.permissions"})
    Optional<User> findByEmail(String email);
    boolean existsByEmailIgnoreCase(String email);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"role", "role.permissions"})
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    // Find users by role name (e.g., "CUSTOMER", "ADMIN")
    List<User> findByRoleRoleName(String roleName);

    // Search users by name or email (case-insensitive)
    List<User> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(String name, String email);

    // Count by active status
    long countByIsActive(boolean isActive);

    // Count staff (non-customer roles)
    long countByRoleRoleNameNot(String roleName);

    // Find by role name and active status
    List<User> findByRoleRoleNameNotAndIsActive(String roleName, boolean isActive);
}
