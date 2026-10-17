package com.travelgo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelgo.entity.ApprovalRequest;
import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.ApprovalRequestRepository;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.service.ApprovalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Transactional
class ApprovalServiceSecurityTests {

    private static final String PASSWORD = "ApprovalPass123!";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired ApprovalService approvals;
    @Autowired ApprovalRequestRepository approvalRequests;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired com.travelgo.repository.PermissionRepository permissions;

    @AfterEach void clearAuthentication() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    private void login(User user) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.getEmail(), "unused",
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test
    void staffCreationPayloadStoresOnlyABcryptHashAndApprovesWithThatHash() throws Exception {
        User requester = administrator("requester");
        User reviewer = administrator("reviewer");
        visaOfficerRole();
        String staffEmail = uniqueEmail("staff");

        login(requester);
        approvals.submitRequest("STAFF", null, "CREATE", staffPayload(staffEmail, PASSWORD), requester.getEmail());

        ApprovalRequest request = approvalRequests.findByStatusOrderByCreatedAtDesc("PENDING").stream()
                .filter(candidate -> requester.getId().equals(candidate.getRequestedBy().getId()))
                .findFirst().orElseThrow();
        JsonNode stored = JSON.readTree(request.getPayload());
        String storedHash = stored.path("passwordHash").asText();

        assertFalse(request.getPayload().contains(PASSWORD));
        assertTrue(stored.path("password").isMissingNode());
        assertTrue(passwordEncoder.matches(PASSWORD, storedHash));

        login(reviewer);
        approvals.approveRequest(request.getId(), reviewer.getEmail());

        User created = users.findByEmail(staffEmail).orElseThrow();
        assertEquals(storedHash, created.getPassword());
        assertTrue(passwordEncoder.matches(PASSWORD, created.getPassword()));
        ApprovalRequest approved = approvalRequests.findById(request.getId()).orElseThrow();
        assertEquals("APPROVED", approved.getStatus());
        assertFalse(approved.getPayload().contains(PASSWORD));
    }

    @Test
    void legacyPendingRequestIsHashedWhenApprovedAndRedactedWhenRejected() throws Exception {
        User requester = administrator("legacy-requester");
        User reviewer = administrator("legacy-reviewer");
        visaOfficerRole();

        String approvedEmail = uniqueEmail("legacy-approved");
        ApprovalRequest legacyApproval = legacyRequest(requester, approvedEmail, PASSWORD);
        login(reviewer);
        approvals.approveRequest(legacyApproval.getId(), reviewer.getEmail());

        ApprovalRequest approved = approvalRequests.findById(legacyApproval.getId()).orElseThrow();
        JsonNode approvedPayload = JSON.readTree(approved.getPayload());
        assertEquals("APPROVED", approved.getStatus());
        assertFalse(approved.getPayload().contains(PASSWORD));
        assertTrue(approvedPayload.path("password").isMissingNode());
        assertTrue(passwordEncoder.matches(PASSWORD, approvedPayload.path("passwordHash").asText()));
        assertTrue(passwordEncoder.matches(PASSWORD, users.findByEmail(approvedEmail).orElseThrow().getPassword()));

        String rejectedEmail = uniqueEmail("legacy-rejected");
        ApprovalRequest legacyRejection = legacyRequest(requester, rejectedEmail, PASSWORD);
        approvals.rejectRequest(legacyRejection.getId(), reviewer.getEmail());

        ApprovalRequest rejected = approvalRequests.findById(legacyRejection.getId()).orElseThrow();
        assertEquals("REJECTED", rejected.getStatus());
        assertFalse(rejected.getPayload().contains(PASSWORD));
        assertTrue(JSON.readTree(rejected.getPayload()).path("credentialsRemoved").asBoolean());
        assertTrue(users.findByEmail(rejectedEmail).isEmpty());
    }

    private ApprovalRequest legacyRequest(User requester, String email, String rawPassword) throws Exception {
        ApprovalRequest request = new ApprovalRequest();
        request.setEntityType("STAFF");
        request.setActionType("CREATE");
        request.setRequestedBy(requester);
        request.setStatus("PENDING");
        request.setPayload(JSON.writeValueAsString(staffPayload(email, rawPassword)));
        return approvalRequests.saveAndFlush(request);
    }

    private Map<String, Object> staffPayload(String email, String password) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "Approval Staff");
        payload.put("email", email);
        payload.put("password", password);
        payload.put("phone", "123456789");
        payload.put("address", "Approval test");
        payload.put("roleName", "VISA_OFFICER");
        return payload;
    }

    private User administrator(String prefix) {
        Role adminRole = roles.findByRoleName("ADMIN")
                .orElseGet(() -> roles.save(new Role("ADMIN", "Administrator")));
        var assigned = new java.util.HashSet<>(adminRole.getPermissions());
        for (String name : java.util.List.of("USER_MANAGE", "STAFF_MANAGE")) assigned.add(permissions.findByPermissionName(name)
                .orElseGet(() -> permissions.save(new com.travelgo.entity.Permission(name, "Test administrator permission", "ADMIN"))));
        adminRole.setPermissions(assigned); roles.saveAndFlush(adminRole);
        return users.saveAndFlush(new User("Approval " + prefix, uniqueEmail(prefix),
                passwordEncoder.encode(PASSWORD), adminRole));
    }

    private void visaOfficerRole() {
        roles.findByRoleName("VISA_OFFICER")
                .orElseGet(() -> roles.save(new Role("VISA_OFFICER", "Visa Officer")));
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.invalid";
    }
}
