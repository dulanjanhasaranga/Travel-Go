package com.travelgo.repository;

import com.travelgo.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Repository for Role entity.
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByRoleName(String roleName);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Role r where r.roleName=:roleName")
    Optional<Role> lockByRoleName(@org.springframework.data.repository.query.Param("roleName") String roleName);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Role r where r.id=:id")
    Optional<Role> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    boolean existsByRoleName(String roleName);
}
