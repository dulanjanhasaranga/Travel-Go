package com.travelgo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
@Transactional
class AdminManagementTests {
    private static final String PASSWORD = "Synthetic-admin123!";
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired ApprovalRequestRepository requests;
    @Autowired AuditLogRepository auditLogs;
    @Autowired ApprovalService approvals;
    @Autowired UserService accounts;
    @Autowired RoleService roleService;
    @Autowired SystemSettingsService settings;
    @Autowired SystemSettingsRepository settingsRepository;
    @Autowired WebApplicationContext context;
    User requester, reviewer, customer;
    Role administrator, consultant, officer, customerRole;
    MockMvc mvc;

    @BeforeEach void fixture() {
        administrator = role("ADMIN"); consultant = role("TRAVEL_CONSULTANT"); officer = role("VISA_OFFICER"); customerRole = role("CUSTOMER");
        Set<Permission> assigned = new HashSet<>();
        for (String name : List.of("USER_VIEW", "USER_MANAGE", "STAFF_VIEW", "STAFF_MANAGE", "ROLE_VIEW", "ROLE_MANAGE", "SYSTEM_SETTINGS_VIEW", "SYSTEM_SETTINGS_MANAGE")) {
            assigned.add(permissions.findByPermissionName(name).orElseGet(() -> permissions.save(new Permission(name, name, "ADMIN"))));
        }
        administrator.setPermissions(assigned); roles.saveAndFlush(administrator);
        requester = account("Requester", administrator); reviewer = account("Reviewer", administrator); customer = account("Customer", customerRole);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        login(requester);
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void administrativeServicesRequireTheSignedInActiveAdministratorAndStoredPermission() {
        SecurityContextHolder.clearContext();
        assertThrows(AccessDeniedException.class, () -> approvals.submitRequest("USER", customer.getId(), "TOGGLE_STATUS", Map.of(), requester.getEmail()));
        login(customer);
        assertThrows(AccessDeniedException.class, () -> approvals.submitRequest("USER", customer.getId(), "TOGGLE_STATUS", Map.of(), requester.getEmail()));
        login(requester);
        assertThrows(AccessDeniedException.class, () -> approvals.submitRequest("USER", customer.getId(), "TOGGLE_STATUS", Map.of(), reviewer.getEmail()));
        requester.setActive(false); users.saveAndFlush(requester);
        assertThrows(AccessDeniedException.class, () -> accounts.updateUser(customer.getId(), "Changed", "", ""));
        requester.setActive(true); users.saveAndFlush(requester);
        administrator.setPermissions(new HashSet<>()); roles.saveAndFlush(administrator);
        assertThrows(AccessDeniedException.class, () -> settings.updateCompanyInfo("Changed", "", ""));
        assertThrows(AccessDeniedException.class, () -> approvals.submitRequest("USER", customer.getId(), "TOGGLE_STATUS", Map.of(), requester.getEmail()));
        assertEquals(0, requests.findAll().stream().filter(r -> r.getRequestedBy().getId().equals(requester.getId())).count());
        assertEquals("Customer", users.findById(customer.getId()).orElseThrow().getName());
    }

    @Test void unsupportedActionsInvalidAccountDetailsAndPendingDuplicatesNeverCreateBogusApprovals() {
        long before = requests.count();
        assertThrows(IllegalArgumentException.class, () -> approvals.submitRequest("HOTEL", customer.getId(), "UPDATE", Map.of(), requester.getEmail()));
        assertThrows(IllegalArgumentException.class, () -> approvals.submitRequest("USER", customer.getId(), "DELETE", Map.of(), requester.getEmail()));
        assertThrows(IllegalArgumentException.class, () -> approvals.submitRequest("USER", customer.getId(), "UPDATE", Map.of("name", " ", "phone", "", "address", ""), requester.getEmail()));
        var unsupportedRole = staffPayload(email()); unsupportedRole.put("roleName", "CUSTOMER");
        assertThrows(IllegalArgumentException.class, () -> approvals.submitRequest("STAFF", null, "CREATE", unsupportedRole, requester.getEmail()));
        assertEquals(before, requests.count());
        var valid = staffPayload(email());
        approvals.submitRequest("STAFF", null, "CREATE", valid, requester.getEmail());
        assertThrows(IllegalStateException.class, () -> approvals.submitRequest("STAFF", null, "CREATE", valid, requester.getEmail()));
        assertEquals(before + 1, requests.count());
    }

    @Test void twoPersonApprovalAppliesTheChangeOnceAndCannotBeSpoofed() {
        ApprovalRequest request = request("USER", customer.getId(), "UPDATE", Map.of("name", "Updated customer", "phone", "123", "address", "Updated address"));
        assertThrows(IllegalStateException.class, () -> approvals.approveRequest(request.getId(), requester.getEmail()));
        assertThrows(AccessDeniedException.class, () -> approvals.approveRequest(request.getId(), reviewer.getEmail()));
        login(reviewer); approvals.approveRequest(request.getId(), reviewer.getEmail());
        assertEquals("Updated customer", users.findById(customer.getId()).orElseThrow().getName());
        assertEquals("APPROVED", requests.findById(request.getId()).orElseThrow().getStatus());
        long auditCount = auditLogs.count();
        assertThrows(IllegalStateException.class, () -> approvals.approveRequest(request.getId(), reviewer.getEmail()));
        assertThrows(IllegalStateException.class, () -> approvals.rejectRequest(request.getId(), reviewer.getEmail()));
        assertEquals(auditCount, auditLogs.count());
        assertTrue(auditLogs.findAll().stream().anyMatch(log -> "APPROVE_CHANGE".equals(log.getAction()) && reviewer.getEmail().equals(log.getUsername())));
    }

    @Test void changedTargetsCannotBeOverwrittenByOlderPendingRequests() {
        ApprovalRequest request = request("USER", customer.getId(), "UPDATE", Map.of("name", "Old requested name", "phone", "", "address", ""));
        customer.setName("More recent profile"); users.saveAndFlush(customer);
        login(reviewer);
        var failure = assertThrows(IllegalStateException.class, () -> approvals.approveRequest(request.getId(), reviewer.getEmail()));
        assertTrue(failure.getMessage().contains("account changed"));
        assertEquals("More recent profile", users.findById(customer.getId()).orElseThrow().getName());
        assertEquals("PENDING", requests.findById(request.getId()).orElseThrow().getStatus());
    }

    @Test void statusRequestsCaptureTheirIntentAndDuplicatePendingTogglesAreRejected() {
        ApprovalRequest request = request("USER", customer.getId(), "TOGGLE_STATUS", Map.of());
        assertTrue(request.getPayload().contains("\"active\":false"));
        assertThrows(IllegalStateException.class, () -> approvals.submitRequest("USER", customer.getId(), "TOGGLE_STATUS", Map.of(), requester.getEmail()));
        login(reviewer); approvals.approveRequest(request.getId(), reviewer.getEmail());
        assertFalse(users.findById(customer.getId()).orElseThrow().isActive());
        assertThrows(IllegalStateException.class, () -> approvals.approveRequest(request.getId(), reviewer.getEmail()));
        assertFalse(users.findById(customer.getId()).orElseThrow().isActive());
    }

    @Test void lastActiveAdministratorCannotBeDeactivatedOrDemoted() {
        users.findByRoleRoleName("ADMIN").stream().filter(u -> !requester.getId().equals(u.getId())).forEach(u -> { u.setActive(false); users.save(u); });
        users.flush();
        assertEquals(1, users.countByRoleRoleNameAndIsActive("ADMIN", true));
        assertThrows(IllegalStateException.class, () -> accounts.toggleUserStatus(requester.getId()));
        assertThrows(IllegalStateException.class, () -> accounts.assignRole(requester.getId(), customerRole.getId()));
        assertThrows(IllegalStateException.class, () -> accounts.updateStaff(requester.getId(), "Requester", "", "", "TRAVEL_CONSULTANT"));
        assertTrue(users.findById(requester.getId()).orElseThrow().isActive());
        assertEquals("ADMIN", users.findById(requester.getId()).orElseThrow().getRole().getRoleName());
    }

    @Test void permissionUpdatesRejectMissingIdsAndPreserveAdministratorRoleManagement() {
        var before = new HashSet<>(administrator.getPermissions());
        assertThrows(IllegalArgumentException.class, () -> roleService.updateRolePermissions(administrator.getId(), Set.of(Long.MAX_VALUE)));
        assertThrows(IllegalStateException.class, () -> roleService.updateRolePermissions(administrator.getId(), Set.of()));
        assertEquals(before, administrator.getPermissions());
        Permission view = permissions.findByPermissionName("USER_VIEW").orElseThrow();
        roleService.updateRolePermissions(consultant.getId(), Set.of(view.getId()));
        assertEquals(Set.of(view), roles.findById(consultant.getId()).orElseThrow().getPermissions());
        assertTrue(auditLogs.findAll().stream().anyMatch(log -> "UPDATE_PERMISSIONS".equals(log.getAction()) && consultant.getId().equals(log.getEntityId())));
    }

    @Test void settingsRejectInvalidInputBeforeChangingStoredPublicInformation() {
        settings.updateCompanyInfo("Test agency", "Description", "https://example.invalid");
        settings.updateContactInfo("support@example.invalid", "0763233204", "Synthetic test address");
        assertThrows(IllegalArgumentException.class, () -> settings.updateCompanyInfo("Changed", "", "javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> settings.updateCompanyInfo("Changed", "", "https://name:password@example.invalid"));
        assertThrows(IllegalArgumentException.class, () -> settings.updateContactInfo("invalid", "123", "Changed"));
        assertThrows(IllegalArgumentException.class, () -> settings.updateContactInfo("support@example.invalid", "1".repeat(21), "Changed"));
        assertThrows(IllegalArgumentException.class, () -> settings.updateWebsiteStatus("PAUSED", "Changed"));
        assertThrows(IllegalArgumentException.class, () -> settings.updateWebsiteStatus("MAINTENANCE", " "));
        assertEquals("Test agency", settings.getSettings().getCompanyName());
        assertEquals("support@example.invalid", settings.getSettings().getCompanyEmail());
        assertEquals("ACTIVE", settings.getSettings().getWebsiteStatus());
    }

    @Test void readableSchedulesAndValidWeekdayJsonAreSupportedButMalformedSchedulesAreRejected() {
        settings.updateBusinessHours("Monday to Friday: opening hours available on request");
        assertTrue(settings.getSettings().getDisplayBusinessHours().contains("opening hours available on request"));
        settings.updateBusinessHours("{\"monday\":\"9:00 AM - 5:00 PM\",\"sunday\":\"Closed\"}");
        assertTrue(settings.getSettings().getDisplayBusinessHours().contains("Sunday: Closed"));
        String before = settings.getSettings().getBusinessHours();
        assertThrows(IllegalArgumentException.class, () -> settings.updateBusinessHours("{\"monday\":"));
        assertThrows(IllegalArgumentException.class, () -> settings.updateBusinessHours("{\"holiday\":\"Closed\"}"));
        assertThrows(IllegalArgumentException.class, () -> settings.updateBusinessHours("[\"Closed\"]"));
        assertEquals(before, settings.getSettings().getBusinessHours());
    }

    @Test void settingsKeepAnExistingSingletonEvenWhenItsGeneratedIdIsNotOne() {
        settingsRepository.deleteAll(); settingsRepository.flush();
        SystemSettings discarded = settingsRepository.saveAndFlush(new SystemSettings());
        settingsRepository.delete(discarded); settingsRepository.flush();
        SystemSettings restored = new SystemSettings(); restored.setCompanyName("Restored agency");
        restored.setCompanyEmail("restored@example.invalid"); settingsRepository.saveAndFlush(restored);
        assertNotEquals(1L, restored.getId());
        assertEquals(restored.getId(), settings.getSettings().getId());
        settings.updateCompanyInfo("Updated restored agency", "Preserved record", "https://example.invalid");
        assertEquals(1, settingsRepository.count());
        assertEquals(restored.getId(), settings.getSettings().getId());
        assertEquals("Updated restored agency", settings.getSettings().getCompanyName());
    }

    @Test void ambiguousMultipleSettingsRowsRequireDatabaseReviewBeforeMutation() {
        SystemSettings original = settings.getSettings();
        String previousEmail = original.getCompanyEmail();
        settingsRepository.saveAndFlush(new SystemSettings());
        var failure = assertThrows(IllegalStateException.class, () -> settings.updateContactInfo("changed@example.invalid", "123", "Changed"));
        assertTrue(failure.getMessage().contains("Multiple system settings records"));
        assertEquals(previousEmail, settingsRepository.findById(original.getId()).orElseThrow().getCompanyEmail());
        assertEquals(2, settingsRepository.count());
    }

    @Test void approvalViewShowsReadableChangesAndNeverDisplaysPasswordsHashesOrInternalSnapshots() throws Exception {
        var payload = staffPayload(email()); request("STAFF", null, "CREATE", payload);
        String storedHash = JSON.readTree(latestRequest().getPayload()).path("passwordHash").asText();
        ApprovalRequest legacy = legacyRequest("STAFF", "CREATE", staffPayload(email()));
        request("USER", customer.getId(), "UPDATE", Map.of("name", "Requested visible name", "phone", "123", "address", "Test"));
        String html = mvc.perform(get("/admin/approvals").with(user(requester.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Requested changes")); assertTrue(html.contains("Requested visible name"));
        assertFalse(html.contains(PASSWORD)); assertFalse(html.contains(storedHash)); assertFalse(html.contains("accountSnapshot"));
        assertFalse(html.contains("View Payload"));
        assertEquals("Set securely; hidden from reviewers", approvals.getDisplayChanges(legacy).get("Password"));
    }

    @Test void unsupportedHistoricalApprovalsCannotSilentlySucceedAndCanBeRejectedWithoutMutatingAccounts() throws Exception {
        ApprovalRequest legacy = legacyRequest("UNKNOWN", "DELETE", Map.of("password", PASSWORD, "passwordHash", "hash"));
        login(reviewer);
        assertThrows(IllegalArgumentException.class, () -> approvals.approveRequest(legacy.getId(), reviewer.getEmail()));
        approvals.rejectRequest(legacy.getId(), reviewer.getEmail());
        assertEquals("REJECTED", legacy.getStatus()); assertFalse(legacy.getPayload().contains(PASSWORD)); assertFalse(legacy.getPayload().contains("passwordHash"));
        assertEquals("Customer", customer.getName());
    }

    @Test void approvalFailsWhenTheRequestingAdministratorsAccessHasChanged() {
        ApprovalRequest request = request("USER", customer.getId(), "TOGGLE_STATUS", Map.of());
        requester.setActive(false); users.saveAndFlush(requester);
        login(reviewer);
        assertThrows(IllegalStateException.class, () -> approvals.approveRequest(request.getId(), reviewer.getEmail()));
        assertTrue(customer.isActive()); assertEquals("PENDING", request.getStatus());
        approvals.rejectRequest(request.getId(), reviewer.getEmail()); assertEquals("REJECTED", request.getStatus());
    }

    @Test void adminFormsRequireTheCorrectRoleCsrfAndBoundedStaffDetails() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/admin/settings").with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/settings/company").with(user(requester.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN")))
                .param("companyName", "Unauthorized write").param("companyDescription", "").param("websiteUrl", ""))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/staff/" + customer.getId() + "/edit").with(user(requester.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))))
                .andExpect(redirectedUrl("/admin/staff")).andExpect(flash().attributeExists("errorMessage"));
        long before = requests.count();
        mvc.perform(post("/admin/staff/create").with(user(requester.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))).with(csrf())
                .param("name", "Valid name").param("email", "x".repeat(145) + "@example.invalid").param("password", PASSWORD)
                .param("roleName", "VISA_OFFICER").param("phone", "").param("address", ""))
                .andExpect(status().isOk()).andExpect(view().name("admin/staff-form")).andExpect(model().attributeHasFieldErrors("staffRequest", "email"));
        assertEquals(before, requests.count());
    }

    private Role role(String name) { return roles.findByRoleName(name).orElseGet(() -> roles.saveAndFlush(new Role(name, name))); }
    private User account(String name, Role role) { return users.saveAndFlush(new User(name, email(), "unused-test-password", role)); }
    private String email() { return UUID.randomUUID() + "@example.invalid"; }
    private void login(User actor) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor.getEmail(), "unused", List.of(new SimpleGrantedAuthority("ROLE_" + actor.getRole().getRoleName())))); }
    private Map<String, Object> staffPayload(String email) { var payload = new LinkedHashMap<String, Object>(); payload.put("name", "New staff"); payload.put("email", email); payload.put("password", PASSWORD); payload.put("phone", "123"); payload.put("address", "Synthetic test"); payload.put("roleName", "VISA_OFFICER"); return payload; }
    private ApprovalRequest request(String entity, Long target, String action, Map<String, Object> payload) { approvals.submitRequest(entity, target, action, payload, requester.getEmail()); return latestRequest(); }
    private ApprovalRequest latestRequest() { return requests.findByStatusOrderByCreatedAtDesc("PENDING").stream().filter(r -> requester.getId().equals(r.getRequestedBy().getId())).findFirst().orElseThrow(); }
    private ApprovalRequest legacyRequest(String entity, String action, Map<String, Object> payload) throws Exception { var request = new ApprovalRequest(); request.setEntityType(entity); request.setActionType(action); request.setRequestedBy(requester); request.setPayload(JSON.writeValueAsString(payload)); return requests.saveAndFlush(request); }
}
