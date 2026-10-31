package com.travelgo;

import com.travelgo.config.*;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.security.*;
import com.travelgo.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Only synthetic H2 accounts; no socket server, real credentials, SMTP, provider or working MySQL access. */
@SpringBootTest(properties = {"spring.config.import=",
        "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false",
        "travelgo.security.staff-mfa.enabled=true", "travelgo.security.staff-mfa.staff-mandatory=true",
        "travelgo.security.staff-mfa.encryption-key=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="})
@ActiveProfiles("test")
@Import({TestDatabaseSafetyConfiguration.class, StaffMfaIntegrationTests.Time.class})
public class StaffMfaIntegrationTests {
    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2030-01-01T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }
    @TestConfiguration static class Time { @Bean @Primary MutableClock mfaClock() { return new MutableClock(); } }
    @Autowired MutableClock clock;
    @Autowired StaffMfaService mfa;
    @Autowired StaffMfaProperties mfaProperties;
    @Autowired StaffMfaCredentialRepository credentials;
    @Autowired StaffMfaRecoveryCodeRepository recovery;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;
    @Autowired WebApplicationContext context;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @Autowired FilterChainProxy filters;
    @Autowired FilterRegistrationBean<StaffMfaGateFilter> registration;
    private MockMvc mvc;
    private User staff;
    private Authentication auth;
    private MockHttpSession session;
    private static final String PASSWORD = "Synthetic-Staff-Only-123!";
    @BeforeEach void fixture() {
        clock.now = Instant.parse("2030-01-01T12:00:00Z");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var tx = new TransactionTemplate(transactionManager);
        staff = tx.execute(s -> {
            Role role = roles.findByRoleName("ADMIN").orElseGet(() -> roles.save(new Role("ADMIN", "Synthetic staff")));
            return users.save(new User("Synthetic MFA staff", UUID.randomUUID() + "@example.invalid", encoder.encode(PASSWORD), role));
        });
        auth = authFor(staff);
        session = session(staff);
    }
    private Authentication authFor(User user) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + RoleNames.canonical(user.getRole().getRoleName())));
        user.getRole().getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p.getPermissionName())));
        return UsernamePasswordAuthenticationToken.authenticated(user.getEmail(), null, authorities);
    }
    private MockHttpSession session(User user) {
        var result = new MockHttpSession(); result.setAttribute("accountPasswordVersion", user.getPassword());
        result.setAttribute("permissionHash", CustomUserDetailsService.permissionHash(user.getRole().getPermissions()));
        return result;
    }
    private String start() {
        assertTrue(mfa.beginEnrollment(auth, session, PASSWORD).success());
        return mfa.setup(auth, session).orElseThrow().secret();
    }
    private StaffMfaService.Result enroll() {
        String secret = start();
        var result = mfa.confirmEnrollment(auth, session, PASSWORD, token(secret));
        assertTrue(result.success()); session.setAttribute(StaffMfaSession.PROOF, result.proof()); return result;
    }
    private String token(String secret) { return StaffMfaCrypto.totp(secret, clock.now.getEpochSecond() / 30, 6); }
    @Test void gateRunsOnceAfterCsrfAndAccountValidationBeforeAuthorization() {
        var list = filters.getFilterChains().get(0).getFilters();
        int csrf = index(list, CsrfFilter.class), account = index(list, AccountSessionFilter.class);
        int gate = index(list, StaffMfaGateFilter.class), authorization = index(list, AuthorizationFilter.class);
        assertTrue(csrf >= 0 && account > csrf && gate > account && authorization > gate);
        assertEquals(1, list.stream().filter(StaffMfaGateFilter.class::isInstance).count()); assertFalse(registration.isEnabled());
    }
    @Test void unverifiedStaffCannotReachBusinessPublicAccountPrivateDocumentOrSocketEndpoints() throws Exception {
        for (String path : List.of("/admin/dashboard", "/staff/dashboard", "/", "/auth/login", "/visa-documents/123/download"))
            mvc.perform(get(path).with(authentication(auth)).session(session)).andExpect(status().isSeeOther()).andExpect(header().string("Location", "/auth/mfa"));
        mvc.perform(get("/ws-travelgo").with(authentication(auth)).session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/protected-mfa-test").with(authentication(auth)).session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/css/staff-mfa.css").with(authentication(auth)).session(session)).andExpect(status().isOk());
    }
    @Test void anonymousAndCustomersCannotUseStaffEnrollmentAndCustomersAreUnaffected() throws Exception {
        mvc.perform(get("/auth/mfa")).andExpect(status().is3xxRedirection());
        User customer = users.save(new User("Synthetic customer", UUID.randomUUID() + "@example.invalid", encoder.encode(PASSWORD),
                roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.save(new Role("CUSTOMER", "Synthetic customer")))));
        Authentication customerAuth = authFor(customer); MockHttpSession customerSession = session(customer);
        mvc.perform(get("/").with(authentication(customerAuth)).session(customerSession)).andExpect(status().isOk());
        mvc.perform(post("/auth/mfa/enroll").with(authentication(customerAuth)).session(customerSession).with(csrf()).param("password", PASSWORD)).andExpect(status().isForbidden());
        assertFalse(credentials.existsById(customer.getId()));
    }
    @Test void enrollmentPostsRequireCsrfAndPasswordAndSetupGetNeverCreatesState() throws Exception {
        mvc.perform(get("/auth/mfa").with(authentication(auth)).session(session)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store, max-age=0"));
        assertFalse(credentials.existsById(staff.getId()));
        mvc.perform(post("/auth/mfa/enroll").with(authentication(auth)).session(session).param("password", PASSWORD)).andExpect(status().isForbidden());
        assertFalse(credentials.existsById(staff.getId()));
        assertFalse(mfa.beginEnrollment(auth, session, "wrong-password").success());
        assertTrue(mfa.setup(auth, session).isEmpty());
        String secret = start(); assertEquals(32, secret.length());
        assertNotEquals(secret, credentials.findById(staff.getId()).orElseThrow().getEncryptedSecret());
    }
    @Test void enrollmentConfirmsCodeAndPersistsOnlyRecoveryHashesBoundToAccount() {
        var result = enroll(); assertEquals(10, result.recoveryCodes().size()); assertTrue(mfa.status(auth, session).verified());
        assertTrue(mfa.status(auth, session).fresh());
        var rows = recovery.findByUserIdAndConsumedAtIsNull(staff.getId()); assertEquals(10, rows.size());
        for (var row : rows) { assertEquals(64, row.getCodeHash().length()); assertFalse(result.recoveryCodes().contains(row.getCodeHash())); }
        assertTrue(mfa.setup(auth, session).isEmpty());
    }
    @Test void spentTotpCannotReplayAcrossSessionsButNextStepCanAuthenticate() {
        String secret = start(); String firstToken = token(secret);
        assertTrue(mfa.confirmEnrollment(auth, session, PASSWORD, firstToken).success());
        assertFalse(mfa.verify(auth, session(staff), firstToken, false).success());
        clock.now = clock.now.plusSeconds(30); assertTrue(mfa.verify(auth, session(staff), token(secret), false).success());
        assertFalse(mfa.verify(auth, session(staff), token(secret), false).success());
    }
    @Test void durableFailuresCooldownAcrossSessionsAndGetDoesNotResetIt() {
        String secret = start();
        assertTrue(mfa.confirmEnrollment(auth, session, PASSWORD, token(secret)).success());
        clock.now = clock.now.plusSeconds(30);
        String wrong = token(secret).equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) assertFalse(mfa.verify(auth, session(staff), wrong, false).success());
        assertTrue(credentials.findById(staff.getId()).orElseThrow().getBlockedUntil().isAfter(clock.now));
        assertTrue(mfa.setup(auth, session).isEmpty());
        assertTrue(mfa.verify(auth, session(staff), token(secret), false).blocked());
        clock.now = clock.now.plusSeconds(301);
        assertTrue(mfa.verify(auth, session(staff), token(secret), false).success());
    }
    @Test void pendingSetupExpiresAndRestartDoesNotResetFailureBudgetOrExtendLiveSetup() {
        String secret = start(); Instant expiry = mfa.setup(auth, session).orElseThrow().expiresAt();
        assertFalse(mfa.confirmEnrollment(auth, session, "wrong-password", token(secret)).success());
        clock.now = clock.now.plusSeconds(30); assertTrue(mfa.beginEnrollment(auth, session, PASSWORD).success());
        assertEquals(secret, mfa.setup(auth, session).orElseThrow().secret()); assertEquals(expiry, mfa.setup(auth, session).orElseThrow().expiresAt());
        assertEquals(1, credentials.findById(staff.getId()).orElseThrow().getFailedAttempts());
        clock.now = clock.now.plusSeconds(601); assertTrue(mfa.setup(auth, session).isEmpty());
        assertFalse(mfa.confirmEnrollment(auth, session, PASSWORD, token(secret)).success());
        assertTrue(mfa.beginEnrollment(auth, session, PASSWORD).success()); assertNotEquals(secret, mfa.setup(auth, session).orElseThrow().secret());
        assertEquals(1, credentials.findById(staff.getId()).orElseThrow().getFailedAttempts());
    }
    @Test void recoveryCodeIsOneUseAndCannotBeSpentByAnotherStaffAccount() {
        var enrollment = enroll(); String code = enrollment.recoveryCodes().get(0);
        Role role = roles.findByRoleName("ADMIN").orElseThrow();
        User other = users.save(new User("Other staff", UUID.randomUUID() + "@example.invalid", encoder.encode(PASSWORD), role));
        var otherAuth = authFor(other); var otherSession = session(other);
        assertTrue(mfa.beginEnrollment(otherAuth, otherSession, PASSWORD).success());
        String otherSecret = mfa.setup(otherAuth, otherSession).orElseThrow().secret();
        assertTrue(mfa.confirmEnrollment(otherAuth, otherSession, PASSWORD, token(otherSecret)).success());
        assertFalse(mfa.verify(otherAuth, otherSession, code, true).success());
        assertTrue(mfa.verify(auth, session(staff), code, true).success());
        assertFalse(mfa.verify(auth, session(staff), code, true).success());
        assertEquals(9, recovery.findByUserIdAndConsumedAtIsNull(staff.getId()).size());
    }
    @Test void concurrentRecoveryUseHasExactlyOneWinner() throws Exception {
        String code = enroll().recoveryCodes().get(0);
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try {
            Callable<Boolean> attempt = () -> { ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS)); return mfa.verify(auth, session(staff), code, true).success(); };
            Future<Boolean> first = executor.submit(attempt), second = executor.submit(attempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(9, recovery.findByUserIdAndConsumedAtIsNull(staff.getId()).size());
        } finally { executor.shutdownNow(); }
    }
    @Test void concurrentTotpUseHasExactlyOneWinner() throws Exception {
        String secret = start(); assertTrue(mfa.confirmEnrollment(auth, session, PASSWORD, token(secret)).success());
        clock.now = clock.now.plusSeconds(30); String code = token(secret);
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try {
            Callable<Boolean> attempt = () -> { ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS)); return mfa.verify(auth, session(staff), code, false).success(); };
            Future<Boolean> first = executor.submit(attempt), second = executor.submit(attempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    @Test void concurrentWrongCodesCommitTheFullFailureBudgetAcrossWaitingTransactions() throws Exception {
        enroll(); ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6), go = new CountDownLatch(1);
        try {
            List<Future<StaffMfaService.Result>> attempts = new ArrayList<>();
            for (int i = 0; i < 6; i++) attempts.add(executor.submit(() -> {
                ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                return mfa.verify(auth, session(staff), "invalid-token", false);
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown(); int blocked = 0;
            for (var attempt : attempts) { var result = attempt.get(20, TimeUnit.SECONDS); assertFalse(result.success()); if (result.blocked()) blocked++; }
            assertEquals(2, blocked);
            var stored = credentials.findById(staff.getId()).orElseThrow();
            assertEquals(clock.now.plusSeconds(300), stored.getBlockedUntil()); assertEquals(0, stored.getFailedAttempts());
        } finally { executor.shutdownNow(); }
    }
    @Test void gatePrimedReadOnlyPersistenceContextCannotReplayOrLoseANewAcceptedCounter() throws Exception {
        String secret = start(); var initial = mfa.confirmEnrollment(auth, session, PASSWORD, token(secret));
        assertTrue(initial.success()); session.setAttribute(StaffMfaSession.PROOF, initial.proof()); clock.now = clock.now.plusSeconds(30);
        var entityManager = entityManagerFactory.createEntityManager();
        var holder = new org.springframework.orm.jpa.EntityManagerHolder(entityManager);
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(entityManagerFactory, holder);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(mfa.status(auth, session).verified()); // Same-context credential was loaded by a read-only gate transaction.
            assertTrue(entityManager.contains(entityManager.find(StaffMfaCredential.class, staff.getId())));
            String acceptedElsewhere = token(secret);
            assertTrue(executor.submit(() -> mfa.verify(auth, session(staff), acceptedElsewhere, false).success()).get(15, TimeUnit.SECONDS));
            assertFalse(mfa.verify(auth, session, acceptedElsewhere, false).success());
            clock.now = clock.now.plusSeconds(30); String acceptedHere = token(secret);
            assertTrue(mfa.verify(auth, session, acceptedHere, false).success());
            assertFalse(executor.submit(() -> mfa.verify(auth, session(staff), acceptedHere, false).success()).get(15, TimeUnit.SECONDS),
                    "A gate-loaded read-only entity must become writable so its accepted counter commits");
        } finally {
            executor.shutdownNow();
            if (org.springframework.transaction.support.TransactionSynchronizationManager.getResource(entityManagerFactory) == holder)
                org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            entityManager.close();
        }
    }
    @Test void concurrentRecoveryRotationRemovesTheLatestBatchDespiteAnEarlierRepeatableReadSnapshot() throws Exception {
        String secret = start(); assertTrue(mfa.confirmEnrollment(auth, session, PASSWORD, token(secret)).success());
        clock.now = clock.now.plusSeconds(30);
        String lowerCounter = token(secret), higherCounter = StaffMfaCrypto.totp(secret, clock.now.getEpochSecond() / 30 + 1, 6);
        CountDownLatch snapshotReady = new CountDownLatch(1), firstCommitted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<StaffMfaService.Result> waiting = executor.submit(() -> {
                var transaction = new TransactionTemplate(transactionManager);
                transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return transaction.execute(status -> {
                    users.findById(staff.getId()).orElseThrow(); // Establish the same kind of consistent snapshot as actor lookup.
                    snapshotReady.countDown();
                    try { assertTrue(firstCommitted.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    return mfa.regenerateRecoveryCodes(auth, session(staff), PASSWORD, higherCounter, false);
                });
            });
            assertTrue(snapshotReady.await(5, TimeUnit.SECONDS));
            var first = mfa.regenerateRecoveryCodes(auth, session, PASSWORD, lowerCounter, false); assertTrue(first.success());
            firstCommitted.countDown(); var second = waiting.get(20, TimeUnit.SECONDS); assertTrue(second.success());
            assertEquals(10, recovery.findByUserIdAndConsumedAtIsNull(staff.getId()).size());
            assertFalse(mfa.verify(auth, session(staff), first.recoveryCodes().get(0), true).success());
            assertTrue(mfa.verify(auth, session(staff), second.recoveryCodes().get(0), true).success());
            session.setAttribute(StaffMfaSession.PROOF, first.proof()); assertFalse(mfa.status(auth, session).verified());
            session.setAttribute(StaffMfaSession.PROOF, second.proof()); assertTrue(mfa.status(auth, session).verified());
        } finally { firstCommitted.countDown(); executor.shutdownNow(); }
    }
    @Test void recoveryRotationRevokesOtherProofsAndOldCodes() {
        var initial = enroll(); var otherSession = session(staff); otherSession.setAttribute(StaffMfaSession.PROOF, initial.proof());
        var rotated = mfa.regenerateRecoveryCodes(auth, session, PASSWORD, initial.recoveryCodes().get(0), true);
        assertTrue(rotated.success()); assertEquals(10, rotated.recoveryCodes().size());
        session.setAttribute(StaffMfaSession.PROOF, rotated.proof()); assertTrue(mfa.status(auth, session).verified());
        assertFalse(mfa.status(auth, otherSession).verified());
        assertFalse(mfa.verify(auth, session(staff), initial.recoveryCodes().get(1), true).success());
    }
    @Test void recoveredAccountCanReplacePhoneWithoutRetainingOldProofsOrCodes() {
        var initial = enroll(); var otherSession = session(staff); otherSession.setAttribute(StaffMfaSession.PROOF, initial.proof());
        var replaced = mfa.replaceAuthenticator(auth, session, PASSWORD, initial.recoveryCodes().get(0), true);
        assertTrue(replaced.success()); assertNull(replaced.proof()); assertFalse(mfa.status(auth, otherSession).verified());
        assertFalse(mfa.status(auth, session).enrolled()); assertEquals(0, recovery.findByUserIdAndConsumedAtIsNull(staff.getId()).size());
        String newSecret = mfa.setup(auth, session).orElseThrow().secret();
        var completed = mfa.confirmEnrollment(auth, session, PASSWORD, token(newSecret)); assertTrue(completed.success());
        session.setAttribute(StaffMfaSession.PROOF, completed.proof()); assertTrue(mfa.status(auth, session).verified());
        assertFalse(mfa.verify(auth, session(staff), initial.recoveryCodes().get(1), true).success());
    }
    @Test void optionalLocalEnrollmentCannotBypassAnExistingFactorDuringPhoneReplacement() {
        var initial = enroll(); mfaProperties.setStaffMandatory(false);
        try {
            assertTrue(mfa.status(auth, session).required());
            assertTrue(mfa.replaceAuthenticator(auth, session, PASSWORD, initial.recoveryCodes().get(0), true).success());
            assertTrue(mfa.status(auth, session(staff)).required());
            assertFalse(mfa.status(auth, session(staff)).verified());
        } finally { mfaProperties.setStaffMandatory(true); }
    }
    @Test void pendingSecretAndConfirmationAreBoundToThePasswordAuthorizedEnrollmentSession() {
        String secret = start(); MockHttpSession another = session(staff);
        assertTrue(mfa.setup(auth, another).isEmpty());
        assertFalse(mfa.beginEnrollment(auth, another, PASSWORD).success());
        assertFalse(mfa.confirmEnrollment(auth, another, PASSWORD, token(secret)).success());
        assertEquals(secret, mfa.setup(auth, session).orElseThrow().secret());
        assertTrue(mfa.confirmEnrollment(auth, session, PASSWORD, token(secret)).success());
    }
    @Test void revokedSessionCannotLearnOrAdoptReplacementSecretEvenAfterSetupExpiry() throws Exception {
        var initial = enroll(); MockHttpSession revoked = session(staff); revoked.setAttribute(StaffMfaSession.PROOF, initial.proof());
        assertTrue(mfa.replaceAuthenticator(auth, session, PASSWORD, initial.recoveryCodes().get(0), true).success());
        String replacement = mfa.setup(auth, session).orElseThrow().secret();
        assertTrue(mfa.setup(auth, revoked).isEmpty());
        mvc.perform(get("/auth/mfa").with(authentication(auth)).session(revoked))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(replacement))));
        assertFalse(mfa.beginEnrollment(auth, revoked, PASSWORD).success());
        assertFalse(mfa.confirmEnrollment(auth, revoked, PASSWORD, token(replacement)).success());
        clock.now = clock.now.plusSeconds(601);
        assertFalse(mfa.beginEnrollment(auth, revoked, PASSWORD).success());
        assertTrue(mfa.beginEnrollment(auth, session, PASSWORD).success());
        assertNotEquals(replacement, mfa.setup(auth, session).orElseThrow().secret());
    }
    @Test void freshStepUpExpiresAndSensitiveMutationNeverReplaysItsBody() throws Exception {
        var initial = enroll(); clock.now = clock.now.plusSeconds(301);
        assertTrue(mfa.status(auth, session).verified()); assertFalse(mfa.status(auth, session).fresh());
        mvc.perform(post("/admin/roles/999/permissions").with(authentication(auth)).session(session).with(csrf()).param("permissionIds", "999"))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location", "/auth/mfa?stepUp=true"));
        var verified = mfa.reauthenticate(auth, session, PASSWORD, initial.recoveryCodes().get(0), true);
        assertTrue(verified.success()); session.setAttribute(StaffMfaSession.PROOF, verified.proof()); assertTrue(mfa.status(auth, session).fresh());
    }
    @Test void proofExpiresAndSuspensionRevokesIt() {
        enroll(); var baseline = StaffMfaSession.proof(session); clock.now = clock.now.plusSeconds(28801);
        assertFalse(mfa.status(auth, session).verified()); clock.now = baseline.verifiedAt();
        assertTrue(mfa.status(auth, session).verified());
        var tx = new TransactionTemplate(transactionManager); tx.executeWithoutResult(s -> { var changed = users.findById(staff.getId()).orElseThrow(); changed.setActive(false); users.save(changed); });
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> mfa.status(auth, session));
    }
    @Test void passwordChangeRevokesProofAndRejectsEnrollmentOrRecovery() {
        var initial = enroll(); var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> { var changed = users.findById(staff.getId()).orElseThrow(); changed.setPassword(encoder.encode("Changed-synthetic-password-123!")); users.save(changed); });
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> mfa.status(auth, session));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> mfa.verify(auth, session, initial.recoveryCodes().get(0), true));
        assertEquals(10, recovery.findByUserIdAndConsumedAtIsNull(staff.getId()).size());
    }
    @Test void roleAndPermissionBaselineChangesCannotRetainProof() {
        enroll(); session.setAttribute("permissionHash", "stale-synthetic-permissions");
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> mfa.status(auth, session));
        session.setAttribute("permissionHash", CustomUserDetailsService.permissionHash(staff.getRole().getPermissions()));
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> { var changed = users.findById(staff.getId()).orElseThrow(); changed.setRole(roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.save(new Role("CUSTOMER", "Synthetic customer")))); users.save(changed); });
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> mfa.status(auth, session));
    }
    @Test void successfulControllerVerificationRotatesSessionIdAndNeverReturnsCode() throws Exception {
        String secret = start(); var result = mfa.confirmEnrollment(auth, session, PASSWORD, token(secret)); assertTrue(result.success());
        clock.now = clock.now.plusSeconds(30); String submitted = token(secret), previousId = session.getId();
        var response = mvc.perform(post("/auth/mfa/verify").with(authentication(auth)).session(session).with(csrf()).param("code", submitted))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/dashboard")).andReturn();
        assertNotEquals(previousId, session.getId()); assertNotNull(session.getAttribute(StaffMfaSession.PROOF));
        assertFalse(response.getResponse().getContentAsString().contains(submitted));
    }
    private static int index(List<jakarta.servlet.Filter> list, Class<?> type) {
        for (int i = 0; i < list.size(); i++) if (type.isInstance(list.get(i))) return i;
        return -1;
    }
}
