package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.ApprovalService;
import com.travelgo.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.config.import=")
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
class AdminAccountConcurrencyTests {
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired ApprovalRequestRepository requests;
    @Autowired UserService accounts;
    @Autowired ApprovalService approvals;
    @Autowired PlatformTransactionManager manager;
    User first, second;
    List<Long> temporarilyDisabled = new ArrayList<>();
    TransactionTemplate tx;

    @BeforeEach void fixture() {
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> {
            Role admin = roles.findByRoleName("ADMIN").orElseGet(() -> roles.save(new Role("ADMIN", "Administrator")));
            var assigned = new HashSet<>(admin.getPermissions());
            for (String name : List.of("USER_MANAGE", "STAFF_MANAGE")) assigned.add(permissions.findByPermissionName(name).orElseGet(() -> permissions.save(new Permission(name, name, "ADMIN"))));
            admin.setPermissions(assigned); roles.save(admin);
            roles.findByRoleName("VISA_OFFICER").orElseGet(() -> roles.save(new Role("VISA_OFFICER", "Visa officer")));
            users.findByRoleRoleName("ADMIN").stream().filter(User::isActive).forEach(user -> { temporarilyDisabled.add(user.getId()); user.setActive(false); users.save(user); });
            first = users.save(new User("Concurrent administrator one", UUID.randomUUID() + "@example.invalid", "unused-test-password", admin));
            second = users.save(new User("Concurrent administrator two", UUID.randomUUID() + "@example.invalid", "unused-test-password", admin));
        });
    }

    @AfterEach void restoreOnlyIsolatedTestAccounts() {
        SecurityContextHolder.clearContext();
        tx.executeWithoutResult(status -> {
            for (Long id : temporarilyDisabled) users.findById(id).ifPresent(user -> { user.setActive(true); users.save(user); });
            for (User fixture : List.of(first, second)) users.findById(fixture.getId()).ifPresent(user -> { user.setActive(false); users.save(user); });
        });
    }

    @Test void concurrentDeactivationOfDifferentAdministratorsKeepsOneActiveAdministrator() throws Exception {
        List<Boolean> results = race(() -> deactivate(first), () -> deactivate(second));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, users.countByRoleRoleNameAndIsActive("ADMIN", true));
        assertNotEquals(users.findById(first.getId()).orElseThrow().isActive(), users.findById(second.getId()).orElseThrow().isActive());
    }

    @Test void simultaneousDuplicateStaffSubmissionsCreateOnePendingRequestAndNoAccount() throws Exception {
        String email = UUID.randomUUID() + "@example.invalid";
        Map<String, Object> payload = Map.of("name", "Concurrent staff", "email", email, "password", "Synthetic-admin123!", "phone", "", "address", "", "roleName", "VISA_OFFICER");
        List<Boolean> results = race(() -> submit(first, payload), () -> submit(second, payload));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, requests.findByStatusOrderByCreatedAtDesc("PENDING").stream().filter(r -> r.getPayload().contains(email)).count());
        assertFalse(users.existsByEmailIgnoreCase(email));
    }

    private boolean deactivate(User actor) {
        login(actor);
        try { tx.executeWithoutResult(status -> accounts.toggleUserStatus(actor.getId())); return true; }
        catch (IllegalStateException expectedLastAdminGuard) {
            assertTrue(expectedLastAdminGuard.getMessage().contains("at least one active administrator")); return false;
        } finally { SecurityContextHolder.clearContext(); }
    }

    private boolean submit(User actor, Map<String, Object> payload) {
        login(actor);
        try { tx.executeWithoutResult(status -> approvals.submitRequest("STAFF", null, "CREATE", payload, actor.getEmail())); return true; }
        catch (IllegalStateException expectedDuplicateGuard) {
            assertTrue(expectedDuplicateGuard.getMessage().contains("already awaiting approval")); return false;
        } finally { SecurityContextHolder.clearContext(); }
    }

    private void login(User actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor.getEmail(), "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @SafeVarargs private final List<Boolean> race(Callable<Boolean>... actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.length);
        CountDownLatch ready = new CountDownLatch(actions.length), start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Callable<Boolean> action : actions) results.add(executor.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency fixture did not start."); return action.call(); }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> result : results) outcomes.add(result.get(30, TimeUnit.SECONDS));
            return outcomes;
        } finally { executor.shutdownNow(); }
    }
}
