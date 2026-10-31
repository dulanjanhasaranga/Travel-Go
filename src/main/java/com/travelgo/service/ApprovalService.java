package com.travelgo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelgo.entity.ApprovalRequest;
import com.travelgo.entity.User;
import com.travelgo.repository.ApprovalRequestRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.repository.RoleRepository;
import com.travelgo.util.PasswordPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApprovalService {

    private final ApprovalRequestRepository approvalRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final AdminAccess adminAccess;
    private final RoleRepository roles;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApprovalService(ApprovalRequestRepository approvalRepository,
                           UserRepository userRepository,
                           UserService userService,
                           PasswordEncoder passwordEncoder, AdminAccess adminAccess, RoleRepository roles, Clock clock) {
        this.approvalRepository = approvalRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.adminAccess = adminAccess;
        this.roles = roles;
        this.clock = clock;
    }

    public List<ApprovalRequest> getPendingRequests() {
        adminAccess.requireAdministrator();
        return approvalRepository.findByStatusOrderByCreatedAtDesc("PENDING");
    }

    public ApprovalRequest getRequestById(Long id) {
        adminAccess.requireAdministrator();
        return approvalRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found"));
    }

    @Transactional
    public void submitRequest(String entityType, Long targetEntityId, String actionType, Map<String, Object> payloadMap, String requestedByEmail) {
        validateOperation(entityType, actionType);
        roles.lockByRoleName("ADMIN").orElseThrow(() -> new IllegalStateException("Administrator role is unavailable."));
        User requester = adminAccess.requireMatchingAdministrator(requestedByEmail, permissionFor(entityType));
        Map<String, Object> payload = validatedPayload(entityType, targetEntityId, actionType, payloadMap);
        rejectDuplicatePendingRequest(targetEntityId, actionType, payload);

        ApprovalRequest request = new ApprovalRequest();
        request.setEntityType(entityType);
        request.setTargetEntityId(targetEntityId);
        request.setActionType(actionType);
        request.setRequestedBy(requester);
        request.setStatus("PENDING");

        try {
            request.setPayload(writePayload(securePayloadForSubmission(entityType, actionType, payload)));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize payload", e);
        }

        approvalRepository.save(request);
        adminAccess.record(requester, "REQUEST_CHANGE", "ApprovalRequest", request.getId(), entityType + " " + actionType + " submitted for a second administrator's review.");
    }

    @Transactional
    public void approveRequest(Long requestId, String reviewerEmail) {
        ApprovalRequest request = approvalRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found"));
        validateOperation(request.getEntityType(), request.getActionType());
        roles.lockByRoleName("ADMIN").orElseThrow(() -> new IllegalStateException("Administrator role is unavailable."));
        User reviewer = adminAccess.requireMatchingAdministrator(reviewerEmail, permissionFor(request.getEntityType()));

        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("Request is already processed");
        }

        if (request.getRequestedBy().getId().equals(reviewer.getId())) {
            throw new IllegalStateException("You cannot approve your own request");
        }

        // Apply changes
        try {
            Map<String, Object> payload = readPayload(request);
            sanitizeLegacyCredentials(request, payload, true);
            requireAuthorizedRequester(request);
            validateApprovalTarget(request, payload);
            
            if ("STAFF".equals(request.getEntityType()) || "USER".equals(request.getEntityType())) {
                if ("CREATE".equals(request.getActionType())) {
                    userService.createStaffAccountWithPasswordHash(
                            (String) payload.get("name"),
                            (String) payload.get("email"),
                            requiredPasswordHash(payload),
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
        request.setReviewedAt(LocalDateTime.now(clock));
        approvalRepository.save(request);
        adminAccess.record(reviewer, "APPROVE_CHANGE", "ApprovalRequest", request.getId(), request.getEntityType() + " " + request.getActionType() + " approved and applied.");
    }

    @Transactional
    public void rejectRequest(Long requestId, String reviewerEmail) {
        ApprovalRequest request = approvalRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found"));
        User reviewer = adminAccess.requireMatchingAdministrator(reviewerEmail, "USER_MANAGE", "STAFF_MANAGE");

        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalStateException("Request is already processed");
        }

        if (request.getRequestedBy().getId().equals(reviewer.getId())) {
            throw new IllegalStateException("You cannot reject your own request");
        }

        try {
            Map<String, Object> payload = readPayload(request);
            sanitizeLegacyCredentials(request, payload, false);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse payload", e);
        }

        request.setStatus("REJECTED");
        request.setReviewedBy(reviewer);
        request.setReviewedAt(LocalDateTime.now(clock));
        approvalRepository.save(request);
        adminAccess.record(reviewer, "REJECT_CHANGE", "ApprovalRequest", request.getId(), request.getEntityType() + " " + request.getActionType() + " rejected without changing the account.");
    }

    private void validateOperation(String entityType, String actionType) {
        boolean supported = actionType != null && (("STAFF".equals(entityType) && java.util.Set.of("CREATE", "UPDATE", "TOGGLE_STATUS").contains(actionType))
                || ("USER".equals(entityType) && java.util.Set.of("CREATE", "UPDATE", "TOGGLE_STATUS", "ASSIGN_ROLE").contains(actionType)));
        if (!supported) throw new IllegalArgumentException("This approval action is not supported. Reject the request and submit a supported account change.");
    }

    private String permissionFor(String entityType) {
        return "STAFF".equals(entityType) ? "STAFF_MANAGE" : "USER_MANAGE";
    }

    private Map<String, Object> validatedPayload(String entityType, Long targetId, String action, Map<String, Object> supplied) {
        Map<String, Object> payload = new LinkedHashMap<>(supplied == null ? Map.of() : supplied);
        java.util.Set<String> allowed = switch (action) {
            case "CREATE" -> java.util.Set.of("name", "email", "password", "phone", "address", "roleName");
            case "UPDATE" -> "STAFF".equals(entityType) ? java.util.Set.of("name", "phone", "address", "roleName") : java.util.Set.of("name", "phone", "address");
            case "ASSIGN_ROLE" -> java.util.Set.of("roleId");
            default -> java.util.Set.of();
        };
        if (!allowed.containsAll(payload.keySet())) throw new IllegalArgumentException("The request contains unsupported fields.");
        if ("CREATE".equals(action)) {
            if (targetId != null) throw new IllegalArgumentException("A new account request must not target an existing account.");
            validateAccountFields(payload, true);
            String email = text(payload, "email");
            if (email == null || email.length() > 150 || !email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Enter a valid email address.");
            email = email.trim().toLowerCase(java.util.Locale.ROOT);
            if (userRepository.existsByEmailIgnoreCase(email)) throw new IllegalArgumentException("An account already uses this email address.");
            payload.put("email", email);
        } else {
            if (targetId == null || targetId <= 0) throw new IllegalArgumentException("Choose an existing account.");
            User target = userRepository.lockAccount(targetId).orElseThrow(() -> new IllegalArgumentException("The account no longer exists."));
            if ("STAFF".equals(entityType) && "CUSTOMER".equals(target.getRole().getRoleName())) throw new IllegalArgumentException("Customer accounts must be managed from Users.");
            if ("UPDATE".equals(action)) validateAccountFields(payload, "STAFF".equals(entityType));
            if ("ASSIGN_ROLE".equals(action)) {
                var role = roles.findById(positiveRoleId(payload)).orElseThrow(() -> new IllegalArgumentException("The selected role is unavailable."));
                if (!java.util.Set.of("ADMIN", "CUSTOMER", "TRAVEL_CONSULTANT", "VISA_OFFICER").contains(com.travelgo.security.RoleNames.canonical(role.getRoleName()))) throw new IllegalArgumentException("This role has no supported website workspace.");
            }
            if ("TOGGLE_STATUS".equals(action)) payload.put("active", !target.isActive());
            payload.put("accountSnapshot", snapshot(target));
        }
        return payload;
    }

    private void validateAccountFields(Map<String, Object> payload, boolean staffRoleRequired) {
        UserService.validateContactDetails(text(payload, "name"), text(payload, "phone"), text(payload, "address"));
        payload.put("name", text(payload, "name").trim());
        if (staffRoleRequired) userService.requireStaffRole(text(payload, "roleName"));
    }

    private String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) return null;
        if (!(value instanceof String)) throw new IllegalArgumentException("The account details contain an invalid " + key + " value.");
        return (String) value;
    }

    private Long positiveRoleId(Map<String, Object> payload) {
        try {
            long id = Long.parseLong(String.valueOf(payload.get("roleId")));
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Choose a valid account role.");
    }

    private Map<String, Object> snapshot(User user) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", user.getName()); snapshot.put("email", user.getEmail());
        snapshot.put("phone", user.getPhone()); snapshot.put("address", user.getAddress());
        snapshot.put("roleId", user.getRole().getId().toString()); snapshot.put("active", user.isActive());
        snapshot.put("updatedAt", user.getUpdatedAt() == null ? "" : user.getUpdatedAt().toString());
        return snapshot;
    }

    private void validateApprovalTarget(ApprovalRequest request, Map<String, Object> payload) {
        if ("CREATE".equals(request.getActionType())) {
            if (request.getTargetEntityId() != null) throw new IllegalArgumentException("This new-account request targets an existing account and cannot be approved.");
            return;
        }
        User target = userRepository.lockAccount(request.getTargetEntityId()).orElseThrow(() -> new IllegalArgumentException("The account no longer exists."));
        if (!snapshot(target).equals(payload.get("accountSnapshot"))) {
            throw new IllegalStateException("The account changed after this request, or the older request has no account snapshot. Reject it and submit a new request from the current account details.");
        }
        if ("STAFF".equals(request.getEntityType()) && "CUSTOMER".equals(target.getRole().getRoleName())) throw new IllegalArgumentException("Customer accounts must be managed from Users.");
        if ("TOGGLE_STATUS".equals(request.getActionType()) && !Boolean.valueOf(!target.isActive()).equals(payload.get("active"))) throw new IllegalArgumentException("The requested account status is ambiguous. Submit a new request.");
    }

    private void requireAuthorizedRequester(ApprovalRequest request) {
        User requester = userRepository.findById(request.getRequestedBy().getId()).orElseThrow(() -> new IllegalArgumentException("Requester no longer exists."));
        if (!requester.isActive() || !"ADMIN".equals(requester.getRole().getRoleName()) || requester.getRole().getPermissions().stream().noneMatch(p -> permissionFor(request.getEntityType()).equals(p.getPermissionName()))) {
            throw new IllegalStateException("The requester's administrator access changed. Reject this request and submit a new one from an authorized account.");
        }
    }

    private void rejectDuplicatePendingRequest(Long targetId, String action, Map<String, Object> payload) {
        for (ApprovalRequest pending : approvalRepository.findByStatusOrderByCreatedAtDesc("PENDING")) {
            if (!action.equals(pending.getActionType())) continue;
            if (targetId != null && targetId.equals(pending.getTargetEntityId())) throw new IllegalStateException("A request for this account and action is already awaiting approval.");
            if ("CREATE".equals(action)) {
                try {
                    if (String.valueOf(payload.get("email")).equalsIgnoreCase(String.valueOf(readPayload(pending).get("email")))) throw new IllegalStateException("A staff creation request for this email is already awaiting approval.");
                } catch (JsonProcessingException malformedHistoricalPayload) { /* An unrelated historical record does not block a valid submission. */ }
            }
        }
    }

    /** Never send raw passwords, password hashes or internal snapshots to the review page. */
    public Map<String, String> getDisplayChanges(ApprovalRequest request) {
        Map<String, String> changes = new LinkedHashMap<>();
        try {
            var payload = readPayload(request);
            for (String key : List.of("name", "email", "phone", "address", "roleName")) {
                if (payload.containsKey(key)) changes.put(switch (key) { case "roleName" -> "Role"; default -> Character.toUpperCase(key.charAt(0)) + key.substring(1); }, payload.get(key) == null ? "—" : String.valueOf(payload.get(key)));
            }
            if (payload.containsKey("roleId")) changes.put("Role", roles.findById(positiveRoleId(payload)).map(r -> r.getRoleName()).orElse("Unavailable role"));
            if (payload.containsKey("active")) changes.put("Account access", Boolean.TRUE.equals(payload.get("active")) ? "Activate" : "Deactivate");
            if (isAccountCreation(request.getEntityType(), request.getActionType())) changes.put("Password", "Set securely; hidden from reviewers");
            if (changes.isEmpty()) changes.put("Review", "This older request needs a new submission before it can be approved.");
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            changes.put("Review", "The request details could not be read. Reject it and submit a new request.");
        }
        return changes;
    }

    /**
     * Approval payloads must never carry raw credentials to the database.
     * A staff or user creation request receives a BCrypt value before it is
     * serialized; password fields in any other approval type are rejected.
     */
    private Map<String, Object> securePayloadForSubmission(String entityType, String actionType,
                                                             Map<String, Object> suppliedPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (suppliedPayload != null) {
            payload.putAll(suppliedPayload);
        }

        if (isAccountCreation(entityType, actionType)) {
            Object rawPassword = payload.remove("password");
            payload.remove("passwordHash");
            if (!(rawPassword instanceof String password)) {
                throw new IllegalArgumentException("A password is required for a new staff account.");
            }
            PasswordPolicy.requireValid(password);
            payload.put("passwordHash", passwordEncoder.encode(password));
            return payload;
        }

        if (payload.containsKey("password") || payload.containsKey("passwordHash")) {
            throw new IllegalArgumentException("Password changes must use the dedicated account recovery flow.");
        }
        return payload;
    }

    /**
     * Historical pending records created by older versions can contain a raw
     * password. Convert it before approval, or remove it when the request is
     * rejected. The normalized payload is written in the same transaction.
     */
    private void sanitizeLegacyCredentials(ApprovalRequest request, Map<String, Object> payload,
                                           boolean approving) throws JsonProcessingException {
        boolean hasRawPassword = payload.containsKey("password");
        boolean hasPasswordHash = payload.containsKey("passwordHash");
        if (!hasRawPassword && !hasPasswordHash) {
            return;
        }

        if (!isAccountCreation(request.getEntityType(), request.getActionType())) {
            payload.remove("password");
            payload.remove("passwordHash");
            payload.put("credentialsRemoved", true);
            request.setPayload(writePayload(payload));
            return;
        }

        if (!approving) {
            payload.remove("password");
            payload.remove("passwordHash");
            payload.put("credentialsRemoved", true);
            request.setPayload(writePayload(payload));
            return;
        }

        if (hasRawPassword) {
            Object rawPassword = payload.remove("password");
            if (!(rawPassword instanceof String password)) {
                throw new IllegalArgumentException("Legacy approval request has an invalid password value and cannot be approved.");
            }
            PasswordPolicy.requireValid(password);
            payload.put("passwordHash", passwordEncoder.encode(password));
            request.setPayload(writePayload(payload));
        }
    }

    private Map<String, Object> readPayload(ApprovalRequest request) throws JsonProcessingException {
        Map<String, Object> payload = objectMapper.readValue(request.getPayload(), new TypeReference<Map<String, Object>>() {});
        if (payload == null) throw new IllegalArgumentException("The approval request has no readable details.");
        return new LinkedHashMap<>(payload);
    }

    private String writePayload(Map<String, Object> payload) throws JsonProcessingException {
        return objectMapper.writeValueAsString(payload);
    }

    private String requiredPasswordHash(Map<String, Object> payload) {
        Object passwordHash = payload.get("passwordHash");
        if (!(passwordHash instanceof String hash) || hash.isBlank()) {
            throw new IllegalArgumentException("Approval request does not contain a valid password hash.");
        }
        return hash;
    }

    private boolean isAccountCreation(String entityType, String actionType) {
        return "CREATE".equals(actionType)
                && ("STAFF".equals(entityType) || "USER".equals(entityType));
    }
}
