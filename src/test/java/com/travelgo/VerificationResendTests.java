package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic signup lifecycle only. Never reads local.properties or connects to an email provider. */
@SpringBootTest(properties = {"spring.config.import=", "travelgo.mail.enabled=true",
        "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false"})
@ActiveProfiles("test")
@Import({WorkflowIntegrationTests.Time.class, TestDatabaseSafetyConfiguration.class})
class VerificationResendTests {
    @Autowired UserService accounts;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired EmailVerificationTokenRepository tokens;
    @Autowired OutboundEmailRepository emails;
    @Autowired SystemSettingsRepository settings;
    @Autowired PlatformTransactionManager manager;
    @Autowired WorkflowIntegrationTests.MutableClock clock;
    @Autowired WebApplicationContext context;
    @MockitoBean JavaMailSender sender;
    @MockitoBean AccountEmailDispatchService accountDispatch;
    @Autowired EmailDeliveryService delivery;
    MockMvc mvc;
    TransactionTemplate tx;

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        clock.instant = Instant.parse("2030-01-01T12:00:00Z");
        roles.findByRoleName("ADMIN").orElseGet(() -> roles.saveAndFlush(new Role("ADMIN", "Isolated reference")));
        roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.saveAndFlush(new Role("CUSTOMER", "Isolated reference")));
        var site = settings.findFirstByOrderByIdAsc().orElseGet(SystemSettings::new);
        site.setWebsiteStatus("ACTIVE"); settings.saveAndFlush(site);
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        // Token/resend tests use a direct mock scheduler; isolated tests verify the production asynchronous executor.
        when(accountDispatch.submit(anyLong())).thenAnswer(call -> { delivery.deliver(call.getArgument(0)); return true; });
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void oldEmailedGetLinkShowsConfirmationWithoutActivatingOrConsumingToken() throws Exception {
        User customer = registration();
        String token = token(latest(customer));
        mvc.perform(get("/auth/verify-email").param("token", token)).andExpect(status().isOk())
                .andExpect(view().name("auth/verify-email"))
                .andExpect(content().string(containsString("action=\"/auth/verify-email\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("no-referrer")));
        mvc.perform(get("/auth/verify-email").param("token", token)).andExpect(status().isOk());
        assertFalse(users.findById(customer.getId()).orElseThrow().isActive());
        assertFalse(tokens.findById(hash(token)).orElseThrow().isUsed());
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test void verificationAndResendPostsRequireCsrfBeforeAnyMutation() throws Exception {
        User customer = registration();
        String token = token(latest(customer));
        clock.instant = clock.instant.plusSeconds(60);
        mvc.perform(post("/auth/verify-email").param("token", token)).andExpect(status().isForbidden());
        mvc.perform(post("/auth/resend-verification").param("email", customer.getEmail())).andExpect(status().isForbidden());
        assertFalse(users.findById(customer.getId()).orElseThrow().isActive());
        assertFalse(tokens.findById(hash(token)).orElseThrow().isUsed());
        assertEquals(1, messages(customer).size());
    }

    @Test void missingLinkShowsHelpfulPageAndOffersResend() throws Exception {
        mvc.perform(get("/auth/verify-email")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Request a new verification email")))
                .andExpect(content().string(not(containsString("name=\"token\""))));
        mvc.perform(get("/auth/resend-verification")).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test void expiredSignupCanRequestFreshLinkAndPreviousLinkIsRevoked() throws Exception {
        User customer = registration();
        String old = token(latest(customer));
        clock.instant = clock.instant.plusSeconds(86400);
        assertThrows(IllegalArgumentException.class, () -> accounts.verifyEmail(old));
        accounts.resendVerification(customer.getEmail().toUpperCase(Locale.ROOT));
        String replacement = token(latest(customer));
        assertNotEquals(old, replacement);
        assertTrue(tokens.findById(hash(old)).orElseThrow().isUsed());
        assertEquals(clock.instant.plusSeconds(86400), tokens.findById(hash(replacement)).orElseThrow().getExpiresAt());
        assertThrows(IllegalArgumentException.class, () -> accounts.verifyEmail(old));
        mvc.perform(post("/auth/verify-email").with(csrf()).param("token", replacement))
                .andExpect(redirectedUrl("/auth/login"))
                .andExpect(flash().attributeExists("successMessage"));
        assertTrue(users.findById(customer.getId()).orElseThrow().isActive());
        mvc.perform(post("/auth/verify-email").with(csrf()).param("token", replacement))
                .andExpect(redirectedUrl("/auth/login"))
                .andExpect(flash().attributeExists("errorMessage"));
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test void resendCooldownPersistsAcrossRequestsAndOpensAtExactOneMinuteBoundary() {
        User customer = registration();
        accounts.resendVerification(customer.getEmail());
        clock.instant = clock.instant.plusSeconds(59);
        accounts.resendVerification(customer.getEmail());
        assertEquals(1, messages(customer).size());
        clock.instant = clock.instant.plusSeconds(1);
        accounts.resendVerification(customer.getEmail());
        assertEquals(2, messages(customer).size());
        accounts.resendVerification(customer.getEmail());
        assertEquals(2, messages(customer).size());
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test void activeAndAdministrativelyRevokedAccountsCannotRequestNewActivationLinks() {
        User active = registration();
        accounts.verifyEmail(token(latest(active)));
        User suspended = registration();
        tx.executeWithoutResult(status -> tokens.invalidateUnusedForUser(suspended.getId()));
        clock.instant = clock.instant.plusSeconds(86400);
        accounts.resendVerification(active.getEmail());
        accounts.resendVerification(suspended.getEmail());
        assertEquals(1, messages(active).size()); assertEquals(1, messages(suspended).size());
        assertFalse(users.findById(suspended.getId()).orElseThrow().isActive());
        assertTrue(Boolean.TRUE.equals(tx.execute(status -> tokens.findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(suspended.getId()).isEmpty())));
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test void demoStaffAndLegacyInactiveAccountsAreNotEligibleForSelfReactivation() {
        User demo = registration();
        User staff = registration();
        User legacy = tx.execute(status -> users.saveAndFlush(new User("Inactive legacy",
                UUID.randomUUID() + "@example.invalid", "Unused-synthetic-hash", roles.findByRoleName("CUSTOMER").orElseThrow())));
        tx.executeWithoutResult(status -> {
            users.findById(demo.getId()).orElseThrow().setDemoAccount(true);
            users.findById(staff.getId()).orElseThrow().setRole(roles.findByRoleName("ADMIN").orElseThrow());
            users.findById(legacy.getId()).orElseThrow().setActive(false);
        });
        clock.instant = clock.instant.plusSeconds(86400);
        for (User customer : List.of(demo, staff, legacy)) accounts.resendVerification(customer.getEmail());
        assertEquals(1, messages(demo).size()); assertEquals(1, messages(staff).size()); assertEquals(0, messages(legacy).size());
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test void unknownActivePendingAndCooldownResponsesDoNotDiscloseAccountState() throws Exception {
        User pending = registration();
        User active = registration(); accounts.verifyEmail(token(latest(active)));
        String result = null;
        for (String address : List.of(UUID.randomUUID() + "@example.invalid", active.getEmail(), pending.getEmail())) {
            var response = mvc.perform(post("/auth/resend-verification").with(csrf()).param("email", address))
                    .andExpect(redirectedUrl("/auth/resend-verification"))
                    .andExpect(flash().attributeExists("successMessage")).andReturn();
            String message = (String) response.getFlashMap().get("successMessage");
            if (result == null) result = message; else assertEquals(result, message);
        }
        assertEquals(1, messages(pending).size()); assertEquals(1, messages(active).size());
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test void rolledBackResendKeepsPreviousLinkAndCreatesNoMail() {
        User customer = registration();
        String old = token(latest(customer));
        clock.instant = clock.instant.plusSeconds(60);
        tx.executeWithoutResult(status -> { accounts.resendVerification(customer.getEmail()); status.setRollbackOnly(); });
        assertEquals(1, messages(customer).size());
        assertFalse(tokens.findById(hash(old)).orElseThrow().isUsed());
        verify(sender, times(1)).send(any(MimeMessage.class));
        accounts.verifyEmail(old);
        assertTrue(users.findById(customer.getId()).orElseThrow().isActive());
    }

    @Test void concurrentResendsCreateOnlyOneReplacementAndOneAdditionalEmail() throws Exception {
        User customer = registration();
        clock.instant = clock.instant.plusSeconds(60);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { await(start); accounts.resendVerification(customer.getEmail()); });
            var second = pool.submit(() -> { await(start); accounts.resendVerification(customer.getEmail()); });
            start.countDown(); first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
            assertEquals(2, messages(customer).size());
            verify(sender, times(2)).send(any(MimeMessage.class));
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    User registration() { return accounts.registerCustomer("Isolated signup", UUID.randomUUID() + "@example.invalid", "Signup-only-password!", null, null); }
    List<OutboundEmail> messages(User user) { return emails.findAll().stream().filter(e -> user.getEmail().equals(e.getRecipient())).toList(); }
    OutboundEmail latest(User user) { return messages(user).stream().max(Comparator.comparing(OutboundEmail::getId)).orElseThrow(); }
    static String token(OutboundEmail email) { return email.getHtmlBody().split("token=")[1].split("[\"&\\s]", 2)[0]; }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    static void await(CountDownLatch start) {
        try { if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent test did not start"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
