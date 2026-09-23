package com.travelgo.repository;

import com.travelgo.entity.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {

    List<ApprovalRequest> findByStatusOrderByCreatedAtDesc(String status);
    
    List<ApprovalRequest> findByRequestedByIdOrderByCreatedAtDesc(Long requestedById);
}
