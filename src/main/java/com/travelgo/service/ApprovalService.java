package com.travelgo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelgo.entity.ApprovalRequest;
import com.travelgo.entity.User;
import com.travelgo.repository.ApprovalRequestRepository;
import com.travelgo.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class ApprovalService {

    private final ApprovalRequestRepository approvalRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApprovalService(ApprovalRequestRepository approvalRepository,
                           UserRepository userRepository,
                           UserService userService) {
        this.approvalRepository = approvalRepository;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    public List<ApprovalRequest> getPendingRequests() {
        return approvalRepository.findByStatusOrderByCreatedAtDesc("PENDING");
    }

    public ApprovalRequest getRequestById(Long id) {
        return approvalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found"));
    }

    @Transactional
    public void submitRequest(String entityType, Long targetEntityId, String actionType, Map<String, Object> payloadMap, String requestedByEmail) {
        User requester = userRepository.findByEmail(requestedByEmail)
                .orElseThrow(() -> new IllegalArgumentException("Requester not found"));

        ApprovalRequest request = new ApprovalRequest();
        request.setEntityType(entityType);
        request.setTargetEntityId(targetEntityId);
        request.setActionType(actionType);
        request.setRequestedBy(requester);
        request.setStatus("PENDING");

        try {
            request.setPayload(objectMapper.writeValueAsString(payloadMap));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize payload", e);
        }

        approvalRepository.save(request);
    }

    @Transactional
    public void approveRequest(Long requestId, String reviewerEmail) {
        ApprovalRequest request = getRequestById(requestId);
        User reviewer = userRepository.findByEmail(reviewerEmail)
                .orElseThrow(() -> new IllegalArgumentException("Reviewer not found"));

        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("Request is already processed");
        }

        if (request.getRequestedBy().getId().equals(reviewer.getId())) {
            throw new IllegalStateException("You cannot approve your own request");
        }

        // Apply changes
        try {
            Map<String, Object> payload = objectMapper.readValue(request.getPayload(), new TypeReference<Map<String, Object>>() {});
            
            if ("STAFF".equals(request.getEntityType()) || "USER".equals(request.getEntityType())) {
                if ("CREATE".equals(request.getActionType())) {
                    userService.createStaffAccount(
                            (String) payload.get("name"),
                            (String) payload.get("email"),
                            (String) payload.get("password"),
                            (String) payload.get("phone"),
                            (String) payload.get("address"),
                            (String) payload.get("roleName")
                    );
                } else if ("UPDATE".equals(request.getActionType())) {
                    if ("STAFF".equals(request.getEntityType())) {
                        userService.updateStaff(
                                request.getTargetEntityId(),
                                (String) payload.get("name"),
                                (String) payload.get("phone"),
                                (String) payload.get("address"),
                                (String) payload.get("roleName")
                        );
                    } else {
                        userService.updateUser(
                                request.getTargetEntityId(),
                                (String) payload.get("name"),
                                (String) payload.get("phone"),
                                (String) payload.get("address")
                        );
                    }
                } else if ("TOGGLE_STATUS".equals(request.getActionType())) {
                    userService.toggleUserStatus(request.getTargetEntityId());
                } else if ("ASSIGN_ROLE".equals(request.getActionType())) {
                    Long roleId = Long.valueOf(payload.get("roleId").toString());
                    userService.assignRole(request.getTargetEntityId(), roleId);
                }
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse payload", e);
        }

        request.setStatus("APPROVED");
        request.setReviewedBy(reviewer);
        request.setReviewedAt(LocalDateTime.now());
        approvalRepository.save(request);
    }

    @Transactional
    public void rejectRequest(Long requestId, String reviewerEmail) {
        ApprovalRequest request = getRequestById(requestId);
        User reviewer = userRepository.findByEmail(reviewerEmail)
                .orElseThrow(() -> new IllegalArgumentException("Reviewer not found"));

        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("Request is already processed");
        }

        if (request.getRequestedBy().getId().equals(reviewer.getId())) {
            throw new IllegalStateException("You cannot reject your own request");
        }

        request.setStatus("REJECTED");
        request.setReviewedBy(reviewer);
        request.setReviewedAt(LocalDateTime.now());
        approvalRepository.save(request);
    }
}
