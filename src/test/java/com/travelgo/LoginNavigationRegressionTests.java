package com.travelgo;

import com.travelgo.entity.Permission;
import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.PermissionRepository;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
@Transactional
class LoginNavigationRegressionTests {
    private static final String PASSWORD = "Navigation-only-123!";
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired PasswordEncoder encoder;
    @Autowired com.travelgo.repository.SystemSettingsRepository settings;
    @Autowired com.travelgo.repository.AuditLogRepository auditLogs;
    MockMvc mvc;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        // Startup reference-data seeding is disabled in the isolated test profile.
        roles.findByRoleName("ADMIN").orElseGet(() -> roles.saveAndFlush(new Role("ADMIN", "Isolated settings reference")));
        var site = settings.findFirstByOrderByIdAsc().orElseGet(com.travelgo.entity.SystemSettings::new);
        site.setWebsiteStatus("ACTIVE");
        settings.saveAndFlush(site);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private User account(String roleName, String... permissionNames) {
        Role role = roles.findByRoleName(roleName).orElseGet(() -> roles.save(new Role(roleName, "Isolated navigation test")));
        var assigned = new HashSet<Permission>();
        for (String name : permissionNames) assigned.add(permissions.findByPermissionName(name)
                .orElseGet(() -> permissions.save(new Permission(name, "Isolated navigation permission", "BOOKING"))));
        role.setPermissions(assigned);
        roles.saveAndFlush(role);
        return users.saveAndFlush(new User("Navigation test", UUID.randomUUID() + "@example.invalid", encoder.encode(PASSWORD), role));
    }

    private MockHttpSession signIn(User account, String destination) throws Exception {
        SecurityContextHolder.clearContext();
        var result = mvc.perform(post("/auth/login").with(csrf()).param("email", account.getEmail()).param("password", PASSWORD))
                .andExpect(redirectedUrl(destination)).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        SecurityContextHolder.clearContext();
        return session;
    }

    @Test void ordinaryCustomerSignInOpensHomeAndKeepsAccountAccess() throws Exception {
        var session = signIn(account("CUSTOMER"), "/");
        mvc.perform(get("/").session(session)).andExpect(status().isOk()).andExpect(view().name("index"));
        mvc.perform(get("/customer/bookings").session(session)).andExpect(status().isOk());
    }

    @ParameterizedTest @ValueSource(strings = {"TRAVEL_CONSULTANT", "PACKAGE_MANAGER"})
    void consultantWithViewPermissionCanOpenBookingsAfterRealSignIn(String role) throws Exception {
        var session = signIn(account(role, "BOOKING_VIEW"), "/staff/dashboard");
        mvc.perform(get("/staff/bookings").session(session)).andExpect(status().isOk()).andExpect(view().name("staff/bookings"));
    }

    @ParameterizedTest @ValueSource(strings = {"TRAVEL_CONSULTANT", "PACKAGE_MANAGER"})
    void managePermissionAlsoAllowsBookingList(String role) throws Exception {
        var session = signIn(account(role, "BOOKING_MANAGE"), "/staff/dashboard");
        mvc.perform(get("/staff/bookings").session(session)).andExpect(status().isOk());
    }

    @Test void deniedBookingAccessDoesNotShowSignInOrInvalidateSession() throws Exception {
        var account = account("PACKAGE_MANAGER");
        var session = signIn(account, "/staff/dashboard");
        mvc.perform(get("/staff/bookings").session(session)).andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/auth/access-denied"));
        assertFalse(session.isInvalid());
        var security = (SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertEquals(account.getEmail(), security.getAuthentication().getName());
        mvc.perform(get("/auth/access-denied").session(session)).andExpect(status().isForbidden())
                .andExpect(view().name("auth/access-denied"))
                .andExpect(content().string(containsString("Your account cannot open this page.")))
                .andExpect(content().string(not(containsString("id=\"loginForm\""))));
        mvc.perform(get("/staff/dashboard").session(session)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/staff/bookings\""))))
                .andExpect(content().string(not(containsString("href=\"/staff/bookings/cancellations\""))));
    }

    @Test void viewOnlyConsultantCannotSubmitBookingDecisions() throws Exception {
        var session = signIn(account("TRAVEL_CONSULTANT", "BOOKING_VIEW"), "/staff/dashboard");
        for (String action : new String[]{"confirm", "decline", "request-info"}) {
            mvc.perform(post("/staff/bookings/9223372036854775807/" + action).session(session).with(csrf())
                    .param("reason", "Isolated test").param("message", "Isolated test"))
                    .andExpect(status().isForbidden()).andExpect(forwardedUrl("/auth/access-denied"));
        }
    }

    @Test void administratorCanRestoreLegacyBookingAccessWithAuditAndFreshSignIn() throws Exception {
        var consultant = account("PACKAGE_MANAGER");
        var blockedSession = signIn(consultant, "/staff/dashboard");
        mvc.perform(get("/staff/bookings").session(blockedSession)).andExpect(status().isForbidden());

        var administrator = account("ADMIN", "ROLE_MANAGE");
        var adminSession = signIn(administrator, "/admin/dashboard");
        var view = permissions.findByPermissionName("BOOKING_VIEW")
                .orElseGet(() -> permissions.saveAndFlush(new Permission("BOOKING_VIEW", "View bookings", "BOOKING")));
        var manage = permissions.findByPermissionName("BOOKING_MANAGE")
                .orElseGet(() -> permissions.saveAndFlush(new Permission("BOOKING_MANAGE", "Manage bookings", "BOOKING")));
        long auditCount = auditLogs.count();
        Long roleId = consultant.getRole().getId();
        mvc.perform(post("/admin/roles/" + roleId + "/permissions").session(adminSession).with(csrf())
                        .param("permissionIds", view.getId().toString(), manage.getId().toString()))
                .andExpect(redirectedUrl("/admin/roles/" + roleId))
                .andExpect(flash().attribute("successMessage", "Permissions updated."));
        assertEquals(auditCount + 1, auditLogs.count());
        var audit = auditLogs.findByUsernameOrderByTimestampDesc(administrator.getEmail()).get(0);
        assertEquals("UPDATE_PERMISSIONS", audit.getAction());
        assertEquals("Role", audit.getEntityName());
        assertEquals(roleId, audit.getEntityId());
        mvc.perform(get("/staff/bookings").session(blockedSession))
                .andExpect(redirectedUrl("/auth/login?sessionChanged=true"));
        var restoredSession = signIn(consultant, "/staff/dashboard");
        mvc.perform(get("/staff/bookings").session(restoredSession)).andExpect(status().isOk());
    }

    @Test void targetedBookingGrantPreservesOtherPermissionsAndIsIdempotent() throws Exception {
        var consultant = account("PACKAGE_MANAGER", "PACKAGE_VIEW");
        var blocked = signIn(consultant, "/staff/dashboard");
        var administrator = account("ADMIN", "ROLE_MANAGE");
        var session = signIn(administrator, "/admin/dashboard");
        for (var name : new String[]{"BOOKING_VIEW", "BOOKING_MANAGE"}) {
            permissions.findByPermissionName(name).orElseGet(() -> permissions.saveAndFlush(new Permission(name, name, "BOOKING")));
        }
        long before = auditLogs.count();
        var path = "/admin/roles/" + consultant.getRole().getId();
        mvc.perform(get(path).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Consultant booking access")));
        mvc.perform(post(path + "/booking-access").session(session).with(csrf()).param("confirmed", "true"))
                .andExpect(redirectedUrl(path)).andExpect(flash().attribute("successMessage", containsString("Booking access enabled")));
        var names = roles.findById(consultant.getRole().getId()).orElseThrow().getPermissions().stream()
                .map(Permission::getPermissionName).collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("PACKAGE_VIEW", "BOOKING_VIEW", "BOOKING_MANAGE"), names);
        assertEquals(before + 1, auditLogs.count());
        mvc.perform(post(path + "/booking-access").session(session).with(csrf()).param("confirmed", "true"))
                .andExpect(redirectedUrl(path)).andExpect(flash().attribute("successMessage", "This role already has booking access."));
        assertEquals(before + 1, auditLogs.count());
        mvc.perform(get("/staff/bookings").session(blocked)).andExpect(redirectedUrl("/auth/login?sessionChanged=true"));
        var restored = signIn(consultant, "/staff/dashboard");
        mvc.perform(get("/staff/bookings").session(restored)).andExpect(status().isOk());
    }

    @Test void targetedBookingGrantRequiresConfirmationAndCsrf() throws Exception {
        var consultant = account("PACKAGE_MANAGER");
        var session = signIn(account("ADMIN", "ROLE_MANAGE"), "/admin/dashboard");
        long before = auditLogs.count();
        var path = "/admin/roles/" + consultant.getRole().getId();
        mvc.perform(post(path + "/booking-access").session(session).param("confirmed", "true")).andExpect(status().isForbidden());
        mvc.perform(post(path + "/booking-access").session(session).with(csrf()))
                .andExpect(redirectedUrl(path)).andExpect(flash().attribute("errorMessage", containsString("Confirm booking access")));
        assertTrue(roles.findById(consultant.getRole().getId()).orElseThrow().getPermissions().isEmpty());
        assertEquals(before, auditLogs.count());
    }

    @Test void targetedBookingGrantRejectsOtherRolesAndReadOnlyAdministrators() throws Exception {
        var officer = account("VISA_OFFICER", "VISA_VIEW");
        var session = signIn(account("ADMIN", "ROLE_MANAGE"), "/admin/dashboard");
        long before = auditLogs.count();
        var path = "/admin/roles/" + officer.getRole().getId();
        mvc.perform(post(path + "/booking-access").session(session).with(csrf()).param("confirmed", "true"))
                .andExpect(redirectedUrl(path)).andExpect(flash().attribute("errorMessage", containsString("travel consultant role")));
        assertEquals(1, roles.findById(officer.getRole().getId()).orElseThrow().getPermissions().size());
        assertEquals(before, auditLogs.count());
        var readonly = signIn(account("ADMIN", "ROLE_VIEW"), "/admin/dashboard");
        mvc.perform(post(path + "/booking-access").session(readonly).with(csrf()).param("confirmed", "true"))
                .andExpect(status().isForbidden());
        assertEquals(before, auditLogs.count());
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void deniedMutationForwardRendersAccessPageWithOriginalMethod(String method) throws Exception {
        var session = signIn(account("TRAVEL_CONSULTANT", "BOOKING_VIEW"), "/staff/dashboard");
        // MockMvc does not execute forwardedUrl's second dispatch automatically.
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/auth/access-denied")
                        .with(forwarded -> { forwarded.setDispatcherType(jakarta.servlet.DispatcherType.FORWARD); return forwarded; })
                        .session(session).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(view().name("auth/access-denied"))
                .andExpect(content().string(containsString("Your account cannot open this page.")))
                .andExpect(content().string(not(containsString("id=\"loginForm\""))));
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "VISA_OFFICER", "CUSTOMER"})
    void bookingPermissionDoesNotLetOtherRolesEnterConsultantBookings(String role) throws Exception {
        var destination = role.equals("ADMIN") ? "/admin/dashboard" : role.equals("CUSTOMER") ? "/" : "/staff/dashboard";
        var session = signIn(account(role, "BOOKING_MANAGE"), destination);
        mvc.perform(get("/staff/bookings").session(session)).andExpect(status().isForbidden());
    }

    @Test void anonymousBookingManagementStillRequiresSignIn() throws Exception {
        mvc.perform(get("/staff/bookings")).andExpect(redirectedUrl("/auth/login"));
    }
}
