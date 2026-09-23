package com.travelgo.repository;

import com.travelgo.entity.EmailVerificationToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EmailVerificationToken r where r.tokenHash = :hash")
    Optional<EmailVerificationToken> lockByHash(@Param("hash") String hash);

}
