package com.travelgo;

import com.travelgo.entity.EmailVerificationToken;
import com.travelgo.entity.Permission;
import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.EmailVerificationTokenRepository;
import com.travelgo.repository.PermissionRepository;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.service.UserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Import({WorkflowIntegrationTests.Time.class, TestDatabaseSafetyConfiguration.class})
class EmailVerificationRevocationTests {
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired EmailVerificationTokenRepository tokens;
    @Autowired UserService accounts;
    @Autowired PlatformTransactionManager manager;
    @Autowired WorkflowIntegrationTests.MutableClock clock;
    @Autowired WebApplicationContext context;
    TransactionTemplate tx;
    User administrator, pendingCustomer;
    String token;

    @BeforeEach void fixture() throws Exception {
        clock.instant = Instant.parse("2030-01-01T12:00:00Z");
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> {
            Role adminRole = roles.findByRoleName("ADMIN").orElseGet(() -> roles.save(new Role("ADMIN", "Test administrator")));
            var assigned = new HashSet<>(adminRole.getPermissions());
            for (String name : List.of("USER_MANAGE", "STAFF_MANAGE")) {
                assigned.add(permissions.findByPermissionName(name).orElseGet(() -> permissions.save(new Permission(name, name, "ADMIN"))));
            }
            adminRole.setPermissions(assigned);
            roles.save(adminRole);
            Role customerRole = roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.save(new Role("CUSTOMER", "Test customer")));
            roles.findByRoleName("VISA_OFFICER").orElseGet(() -> roles.save(new Role("VISA_OFFICER", "Test visa officer")));
            administrator = users.save(new User("Verification test administrator", UUID.randomUUID() + "@example.invalid", "unused-synthetic-password", adminRole));
            pendingCustomer = new User("Unverified test customer", UUID.randomUUID() + "@example.invalid", "unused-synthetic-password", customerRole);
            pendingCustomer.setActive(false);
            pendingCustomer = users.save(pendingCustomer);
        });
        token = UUID.randomUUID().toString();
        var verification = new EmailVerificationToken();
        verification.setTokenHash(hash(token));
        verification.setUser(pendingCustomer);
        verification.setExpiresAt(clock.instant().plusSeconds(86400));
        tokens.saveAndFlush(verification);
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void pendingCustomerCanVerifyExactlyOnce() throws Exception {
        accounts.verifyEmail(token);
        assertTrue(users.findById(pendingCustomer.getId()).orElseThrow().isActive());
        assertTrue(tokens.findById(hash(token)).orElseThrow().isUsed());
        assertThrows(IllegalArgumentException.class, () -> accounts.verifyEmail(token));
    }

    @Test void expirationBoundaryDoesNotActivateAccount() throws Exception {
        clock.instant = clock.instant().plusSeconds(86400);
        assertThrows(IllegalArgumentException.class, () -> accounts.verifyEmail(token));
        assertFalse(users.findById(pendingCustomer.getId()).orElseThrow().isActive());
        assertFalse(tokens.findById(hash(token)).orElseThrow().isUsed());
    }

    @Test void administratorActivationAndSuspensionInvalidateOldLink() throws Exception {
        loginAdministrator();
        accounts.toggleUserStatus(pendingCustomer.getId());
        accounts.toggleUserStatus(pendingCustomer.getId());
        SecurityContextHolder.clearContext();
        assertThrows(IllegalArgumentException.class, () -> accounts.verifyEmail(token));
        assertFalse(users.findById(pendingCustomer.getId()).orElseThrow().isActive());
        assertTrue(tokens.findById(hash(token)).orElseThrow().isUsed());
    }

    @Test void roleAssignmentCannotUseOldLinkToActivateStaffAccount() throws Exception {
        loginAdministrator();
        Long officerRole = roles.findByRoleName("VISA_OFFICER").orElseThrow().getId();
        accounts.assignRole(pendingCustomer.getId(), officerRole);
        SecurityContextHolder.clearContext();
        assertThrows(IllegalArgumentException.class, () -> accounts.verifyEmail(token));
        assertFalse(users.findById(pendingCustomer.getId()).orElseThrow().isActive());
        assertTrue(tokens.findById(hash(token)).orElseThrow().isUsed());
    }

    @Test void historicalStaffVerificationTokenCannotActivateStaff() {
        tx.executeWithoutResult(status -> users.findById(pendingCustomer.getId()).orElseThrow()
                .setRole(roles.findByRoleName("VISA_OFFICER").orElseThrow()));
        assertThrows(IllegalArgumentException.class, () -> accounts.verifyEmail(token));
        assertFalse(users.findById(pendingCustomer.getId()).orElseThrow().isActive());
    }

    @Test void rolledBackAdministratorChangePreservesLegitimatePendingVerification() throws Exception {
        loginAdministrator();
        tx.executeWithoutResult(status -> {
            accounts.toggleUserStatus(pendingCustomer.getId());
            status.setRollbackOnly();
        });
        assertFalse(users.findById(pendingCustomer.getId()).orElseThrow().isActive());
        assertFalse(tokens.findById(hash(token)).orElseThrow().isUsed());
        SecurityContextHolder.clearContext();
        accounts.verifyEmail(token);
        assertTrue(users.findById(pendingCustomer.getId()).orElseThrow().isActive());
    }

    @Test void revokedVerificationRouteShowsErrorAndKeepsAccountSuspended() throws Exception {
        loginAdministrator();
        accounts.toggleUserStatus(pendingCustomer.getId());
        accounts.toggleUserStatus(pendingCustomer.getId());
        SecurityContextHolder.clearContext();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(post("/auth/verify-email").with(csrf()).param("token", token))
                .andExpect(redirectedUrl("/auth/login"))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(flash().attributeCount(1));
        assertFalse(users.findById(pendingCustomer.getId()).orElseThrow().isActive());
    }

    @Test void verificationWaitingForAccountLockObservesCommittedSuspension() throws Exception {
        tx.executeWithoutResult(status -> users.findById(pendingCustomer.getId()).orElseThrow().setActive(true));
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attemptingVerification = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var suspension = pool.submit(() -> {
                loginAdministrator();
                try {
                    tx.executeWithoutResult(status -> {
                        accounts.toggleUserStatus(pendingCustomer.getId());
                        changed.countDown();
                        try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertTrue(changed.await(10, TimeUnit.SECONDS));
            var verification = pool.submit(() -> {
                attemptingVerification.countDown();
                assertThrows(IllegalArgumentException.class, () -> accounts.verifyEmail(token));
            });
            assertTrue(attemptingVerification.await(10, TimeUnit.SECONDS));
            release.countDown();
            suspension.get(20, TimeUnit.SECONDS);
            verification.get(20, TimeUnit.SECONDS);
            assertFalse(users.findById(pendingCustomer.getId()).orElseThrow().isActive());
            assertTrue(tokens.findById(hash(token)).orElseThrow().isUsed());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    void loginAdministrator() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                administrator.getEmail(), "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    static String hash(String raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
