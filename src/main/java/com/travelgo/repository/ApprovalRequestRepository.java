package com.travelgo.repository;

import com.travelgo.entity.ApprovalRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {

    List<ApprovalRequest> findByStatusOrderByCreatedAtDesc(String status);
    
    List<ApprovalRequest> findByRequestedByIdOrderByCreatedAtDesc(Long requestedById);

    /**
     * Serializes a review decision for one request. Without this lock, two
     * reviewers can both observe PENDING and execute the requested mutation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from ApprovalRequest request where request.id = :id")
    Optional<ApprovalRequest> findByIdForUpdate(@Param("id") Long id);
}
