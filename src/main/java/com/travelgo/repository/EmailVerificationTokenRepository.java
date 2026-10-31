package com.travelgo.repository;

import com.travelgo.entity.EmailVerificationToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, String> {

    // A locking read observes the latest committed resend even under MySQL REPEATABLE READ.
    // Callers acquire the account lock before this token lock.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationToken> findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(Long userId);

    @Query("select r.user.id from EmailVerificationToken r where r.tokenHash = :hash")
    Optional<Long> findUserIdByHash(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EmailVerificationToken r where r.tokenHash = :hash")
    Optional<EmailVerificationToken> lockByHash(@Param("hash") String hash);

    @Modifying
    @Query("update EmailVerificationToken r set r.used = true where r.user.id = :userId and r.used = false")
    int invalidateUnusedForUser(@Param("userId") Long userId);

}
