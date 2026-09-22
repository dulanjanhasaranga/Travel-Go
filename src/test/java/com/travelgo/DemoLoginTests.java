package com.travelgo;

import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.service.CustomUserDetailsService;
import com.travelgo.service.DemoAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:travelgo-demo-login;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.open-in-view=true", "travelgo.demo.enabled=true"})
@ActiveProfiles({"test", "demo"})
class DemoLoginTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired DemoAccountService accounts;
    @Autowired CustomUserDetailsService details;
    @Autowired CsrfTokenRepository csrfTokens;
    MockMvc mvc;

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        users.deleteAll();
        roles.deleteAll();
        accounts.provision(); // Explicit fixture in this isolated H2 database, never startup seeding.
    }

    @ParameterizedTest
    @EnumSource(DemoAccountService.Account.class)
    void eachShortcutRotatesSessionAndOpensItsRealWorkspace(DemoAccountService.Account account) throws Exception {
        MockHttpSession previous = new MockHttpSession();
        String previousId = previous.getId();
        var result = mvc.perform(post("/auth/demo-login").session(previous).with(csrf()).param("account", account.getKey()))
                .andExpect(redirectedUrl(account.getDestination())).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        assertNotEquals(previousId, session.getId());
        var security = (SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertEquals(account.getEmail(), security.getAuthentication().getName());
        assertNull(security.getAuthentication().getCredentials());
        assertEquals(java.util.List.of("ROLE_" + account.getRole()), security.getAuthentication().getAuthorities()
                .stream().map(org.springframework.security.core.GrantedAuthority::getAuthority).toList());
        mvc.perform(get(account.getDestination()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/auth/logout").session(session).with(csrf())).andExpect(redirectedUrl("/auth/login?logout=true"));
        assertTrue(session.isInvalid());
    }

    @Test void shortcutsRequireCsrfAndNeverAcceptArbitraryAccounts() throws Exception {
        mvc.perform(post("/auth/demo-login").param("account", "admin")).andExpect(status().isForbidden());
        mvc.perform(post("/auth/demo-login").with(csrf().useInvalidToken()).param("account", "admin")).andExpect(status().isForbidden());
        mvc.perform(post("/auth/demo-login").with(csrf()).param("account", "admin@travelgo.com")).andExpect(status().isNotFound());
        mvc.perform(get("/auth/demo-login").param("account", "admin")).andExpect(status().isMethodNotAllowed());
    }

    @Test void renderedShortcutsContainNoPasswordsAndProvisioningDoesNotResetAccounts() throws Exception {
        var account = users.findByEmail(DemoAccountService.Account.ADMIN.getEmail()).orElseThrow();
        String hash = account.getPassword();
        long count = users.count();
        mvc.perform(get("/auth/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Quick Demo Access")))
                .andExpect(content().string(not(containsString(hash))))
                .andExpect(content().string(not(containsString("Admin@123"))))
                .andExpect(content().string(containsString("/auth/demo-login")));
        accounts.provision();
        assertEquals(count, users.count());
        assertEquals(hash, users.findById(account.getId()).orElseThrow().getPassword());
    }

    @Test void ordinaryOrReassignedOrInactiveAccountsCannotBeAdoptedByShortcut() throws Exception {
        var account = users.findByEmail(DemoAccountService.Account.ADMIN.getEmail()).orElseThrow();
        account.setDemoAccount(false); users.save(account);
        assertThrows(ResponseStatusException.class, accounts::provision);
        mvc.perform(post("/auth/demo-login").with(csrf()).param("account", "admin"))
                .andExpect(redirectedUrl("/auth/login")).andExpect(flash().attributeExists("errorMessage"));
        account.setDemoAccount(true); account.setActive(false); users.save(account);
        assertThrows(ResponseStatusException.class, () -> accounts.authenticate("admin"));
        account.setActive(true); account.setRole(roles.findByRoleName("CUSTOMER").orElseThrow()); users.save(account);
        assertThrows(ResponseStatusException.class, () -> accounts.authenticate("admin"));
    }

    @Test void aPreexistingAddressCollisionRollsBackProvisioningWithoutAdoptingOrPromotingTheAccount() {
        users.deleteAll();
        Role customer = roles.findByRoleName("CUSTOMER").orElseThrow();
        User ordinary = users.save(new User("Existing customer", DemoAccountService.Account.VISA.getEmail().toUpperCase(java.util.Locale.ROOT),
                "existing-password-hash", customer));

        assertThrows(ResponseStatusException.class, accounts::provision);

        assertEquals(1, users.count(), "Accounts created before the collision must roll back atomically.");
        User preserved = users.findById(ordinary.getId()).orElseThrow();
        assertFalse(preserved.isDemoAccount());
        assertEquals(customer.getId(), preserved.getRole().getId());
        assertEquals(ordinary.getEmail(), preserved.getEmail());
        assertEquals("existing-password-hash", preserved.getPassword());
        assertTrue(users.findByEmail(DemoAccountService.Account.ADMIN.getEmail()).isEmpty());
    }

    @Test void loginRotatesCsrfAndSwitchingWorkspacesReplacesThePreviousRole() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var page = mvc.perform(get("/auth/login").session(session)).andExpect(status().isOk()).andReturn();
        CsrfToken submittedToken = (CsrfToken) page.getRequest().getAttribute("_csrf");
        String previousRawToken = csrfTokens.loadToken(page.getRequest()).getToken();
        var login = mvc.perform(post("/auth/demo-login").session(session)
                        .param("_csrf", submittedToken.getToken()).param("account", "admin").param("redirect", "https://example.invalid/"))
                .andExpect(redirectedUrl("/admin/dashboard")).andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
        var dashboard = mvc.perform(get("/admin/dashboard").session(session)).andExpect(status().isOk()).andReturn();
        // Rendering a CSRF-protected form creates the replacement token after authentication.
        ((CsrfToken) dashboard.getRequest().getAttribute("_csrf")).getToken();
        assertNotEquals(previousRawToken, csrfTokens.loadToken(dashboard.getRequest()).getToken());
        mvc.perform(post("/auth/demo-login").session(session).param("_csrf", submittedToken.getToken()).param("account", "customer"))
                .andExpect(status().isForbidden());
        var switched = mvc.perform(post("/auth/demo-login").session(session).with(csrf()).param("account", "customer"))
                .andExpect(redirectedUrl("/customer/dashboard")).andReturn();
        var switchedSession = (MockHttpSession) switched.getRequest().getSession(false);
        var security = (SecurityContext) switchedSession.getAttribute("SPRING_SECURITY_CONTEXT");
        assertEquals(DemoAccountService.Account.CUSTOMER.getEmail(), security.getAuthentication().getName());
        assertTrue(security.getAuthentication().getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        mvc.perform(get("/customer/dashboard").session(switchedSession)).andExpect(status().isOk());
        mvc.perform(get("/admin/dashboard").session(switchedSession)).andExpect(status().isForbidden());
    }

    @Test void aMissingAccountDoesNotGetRecreatedByPublicLogin() throws Exception {
        users.delete(users.findByEmail(DemoAccountService.Account.ADMIN.getEmail()).orElseThrow());
        long count = users.count();
        mvc.perform(post("/auth/demo-login").with(csrf()).param("account", "admin"))
                .andExpect(redirectedUrl("/auth/login")).andExpect(flash().attributeExists("errorMessage"));
        assertEquals(count, users.count());
    }

    @Test void legacyCatalogueRoleIsReusedWithoutChangingExistingAssignments() throws Exception {
        users.deleteAll(); roles.deleteAll();
        Role legacy = roles.save(new Role("PACKAGE_MANAGER", "Existing catalogue role"));
        User ordinary = users.save(new User("Existing package manager", "existing@example.invalid", "unusable", legacy));
        accounts.provision();
        assertFalse(roles.existsByRoleName("TRAVEL_CONSULTANT"));
        assertEquals(legacy.getId(), users.findById(ordinary.getId()).orElseThrow().getRole().getId());
        assertTrue(details.loadUserByUsername(ordinary.getEmail()).getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_TRAVEL_CONSULTANT")));
        mvc.perform(get("/auth/login")).andExpect(content().string(containsString("Login as Package Manager")));
        var result = mvc.perform(post("/auth/demo-login").with(csrf()).param("account", "catalogue"))
                .andExpect(redirectedUrl("/staff/packages")).andReturn();
        mvc.perform(get("/staff/dashboard").session((MockHttpSession) result.getRequest().getSession(false)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Packages")));
    }
}
