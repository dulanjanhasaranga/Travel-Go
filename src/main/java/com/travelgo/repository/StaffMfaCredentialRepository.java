package com.travelgo.repository;

import com.travelgo.entity.StaffMfaCredential;
import org.springframework.data.jpa.repository.JpaRepository;

/** UserRepository.lockAccount is always acquired first, including creation of an absent credential. */
public interface StaffMfaCredentialRepository extends JpaRepository<StaffMfaCredential, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from StaffMfaCredential c where c.userId=:userId")
    java.util.Optional<StaffMfaCredential> lockByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
