package com.travelgo.repository;
import com.travelgo.entity.PasswordReset;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface PasswordResetRepository extends JpaRepository<PasswordReset,String> {
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 Optional<PasswordReset> findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(Long userId);
 @Query("select r.user.id from PasswordReset r where r.tokenHash=:hash")
 Optional<Long> findUserIdByHash(@org.springframework.data.repository.query.Param("hash") String hash);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from PasswordReset r where r.tokenHash=:hash")
 Optional<PasswordReset> lockByHash(@org.springframework.data.repository.query.Param("hash") String hash);
}
