package com.travelgo.repository;
import com.travelgo.entity.GoogleAccountIdentity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface GoogleAccountIdentityRepository extends JpaRepository<GoogleAccountIdentity, Long> {
    Optional<GoogleAccountIdentity> findBySubject(String subject);
    boolean existsByUser_Id(Long userId);
}
