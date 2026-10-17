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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public registration/recovery journey with fictional recipients and a substituted transport. */
@SpringBootTest(properties = {"spring.config.import=", "travelgo.mail.enabled=true",
        "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false"})
@ActiveProfiles("test")
@Import({WorkflowIntegrationTests.Time.class, TestDatabaseSafetyConfiguration.class})
class AccountRecoveryFlowTests {
    static final String INITIAL = "Acceptance-original-123!";
    static final String REPLACEMENT = "Acceptance-replacement-123!";
    @Autowired WebApplicationContext context;
    @Autowired UserService accounts;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired SystemSettingsRepository settings;
    @Autowired OutboundEmailRepository emails;
    @Autowired PasswordResetRepository resets;
    @Autowired PasswordEncoder encoder;
    @Autowired WorkflowIntegrationTests.MutableClock clock;
    @MockitoBean JavaMailSender sender;
    @MockitoBean AccountEmailDispatchService accountDispatch;
    @Autowired EmailDeliveryService delivery;
    MockMvc mvc;

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        clock.instant = Instant.parse("2030-01-01T12:00:00Z");
        roles.findByRoleName("ADMIN").orElseGet(() -> roles.saveAndFlush(new Role("ADMIN", "Isolated reference")));
        roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.saveAndFlush(new Role("CUSTOMER", "Isolated reference")));
        var site = settings.findFirstByOrderByIdAsc().orElseGet(SystemSettings::new);
        site.setWebsiteStatus("ACTIVE"); settings.saveAndFlush(site);
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        // Keep this lifecycle fixture deterministic; real executor behavior is covered by isolated scheduling tests.
        when(accountDispatch.submit(anyLong())).thenAnswer(call -> { delivery.deliver(call.getArgument(0)); return true; });
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void signupVerificationRecoveryAndNewSignInWorkTogetherAndOldSessionIsRevoked() throws Exception {
        String address = UUID.randomUUID() + "@example.invalid";
        mvc.perform(post("/auth/register").with(csrf()).param("name", "Isolated acceptance")
                        .param("email", address).param("password", INITIAL).param("confirmPassword", INITIAL))
                .andExpect(redirectedUrl("/auth/login"));
        User customer = users.findByEmailIgnoreCase(address).orElseThrow();
        assertFalse(customer.isActive());
        String verification = token(latest(customer, AccountEmailService.VERIFICATION_PREFIX));
        mvc.perform(get("/auth/verify-email").param("token", verification)).andExpect(status().isOk());
        assertFalse(users.findById(customer.getId()).orElseThrow().isActive());
        mvc.perform(post("/auth/verify-email").with(csrf()).param("token", verification))
                .andExpect(redirectedUrl("/auth/login"));
        MockHttpSession oldSession = signIn(address, INITIAL);
        request(customer);
        String reset = token(latest(customer, AccountEmailService.RECOVERY_PREFIX));
        mvc.perform(get("/auth/reset-password").param("token", reset)).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("no-referrer")));
        assertFalse(resets.findById(hash(reset)).orElseThrow().isUsed());
        mvc.perform(post("/auth/reset-password").with(csrf()).param("token", reset)
                        .param("password", REPLACEMENT).param("confirmPassword", REPLACEMENT))
                .andExpect(redirectedUrl("/auth/login"));
        assertTrue(encoder.matches(REPLACEMENT, users.findById(customer.getId()).orElseThrow().getPassword()));
        mvc.perform(get("/customer/bookings").session(oldSession))
                .andExpect(redirectedUrl("/auth/login?sessionChanged=true"));
        assertTrue(oldSession.isInvalid());
        SecurityContextHolder.clearContext();
        mvc.perform(post("/auth/login").with(csrf()).param("email", address).param("password", INITIAL))
                .andExpect(redirectedUrl("/auth/login?error=true"));
        MockHttpSession newSession = signIn(address, REPLACEMENT);
        mvc.perform(get("/customer/bookings").session(newSession)).andExpect(status().isOk());
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test void recoveryAndPasswordChangeRequireCsrfBeforeTokensOrPasswordsChange() throws Exception {
        User customer = verifiedCustomer();
        long before = resets.count();
        mvc.perform(post("/auth/forgot-password").param("email", customer.getEmail())).andExpect(status().isForbidden());
        assertEquals(before, resets.count());
        request(customer);
        String reset = token(latest(customer, AccountEmailService.RECOVERY_PREFIX));
        mvc.perform(post("/auth/reset-password").param("token", reset)
                        .param("password", REPLACEMENT).param("confirmPassword", REPLACEMENT))
                .andExpect(status().isForbidden());
        assertFalse(resets.findById(hash(reset)).orElseThrow().isUsed());
        assertTrue(encoder.matches(INITIAL, users.findById(customer.getId()).orElseThrow().getPassword()));
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test void wrongConfirmationKeepsResetLinkUsableAndSuccessfulResetCannotBeRepeated() throws Exception {
        User customer = verifiedCustomer(); request(customer);
        String reset = token(latest(customer, AccountEmailService.RECOVERY_PREFIX));
        mvc.perform(post("/auth/reset-password").with(csrf()).param("token", reset)
                        .param("password", REPLACEMENT).param("confirmPassword", "Different-password!"))
                .andExpect(view().name("auth/reset-password")).andExpect(model().attributeExists("errorMessage"));
        assertFalse(resets.findById(hash(reset)).orElseThrow().isUsed());
        mvc.perform(post("/auth/reset-password").with(csrf()).param("token", reset)
                        .param("password", REPLACEMENT).param("confirmPassword", REPLACEMENT))
                .andExpect(redirectedUrl("/auth/login"));
        String stored = users.findById(customer.getId()).orElseThrow().getPassword();
        mvc.perform(post("/auth/reset-password").with(csrf()).param("token", reset)
                        .param("password", "Another-valid-password!").param("confirmPassword", "Another-valid-password!"))
                .andExpect(view().name("auth/reset-password")).andExpect(model().attributeExists("errorMessage"));
        assertEquals(stored, users.findById(customer.getId()).orElseThrow().getPassword());
    }

    @Test void exactThirtyMinuteDeadlineRejectsPasswordChangeWithoutConsumingToken() throws Exception {
        User customer = verifiedCustomer(); request(customer);
        String reset = token(latest(customer, AccountEmailService.RECOVERY_PREFIX));
        clock.instant = clock.instant.plusSeconds(1800);
        mvc.perform(post("/auth/reset-password").with(csrf()).param("token", reset)
                        .param("password", REPLACEMENT).param("confirmPassword", REPLACEMENT))
                .andExpect(view().name("auth/reset-password")).andExpect(model().attributeExists("errorMessage"));
        assertFalse(resets.findById(hash(reset)).orElseThrow().isUsed());
        assertTrue(encoder.matches(INITIAL, users.findById(customer.getId()).orElseThrow().getPassword()));
    }

    @Test void inactiveAndUnknownRecoveryRequestsReturnSameNeutralResponseAndSendNoReset() throws Exception {
        User inactive = accounts.registerCustomer("Pending signup", UUID.randomUUID() + "@example.invalid", INITIAL, null, null);
        long before = resets.count();
        var pending = mvc.perform(post("/auth/forgot-password").with(csrf()).param("email", inactive.getEmail()))
                .andExpect(redirectedUrl("/auth/forgot-password")).andExpect(flash().attributeExists("successMessage")).andReturn();
        var unknown = mvc.perform(post("/auth/forgot-password").with(csrf()).param("email", UUID.randomUUID() + "@example.invalid"))
                .andExpect(redirectedUrl("/auth/forgot-password")).andExpect(flash().attributeExists("successMessage")).andReturn();
        assertEquals(pending.getFlashMap().get("successMessage"), unknown.getFlashMap().get("successMessage"));
        assertEquals(before, resets.count());
        assertTrue(emails.findAll().stream().noneMatch(e -> inactive.getEmail().equals(e.getRecipient())
                && e.getEventKey().startsWith(AccountEmailService.RECOVERY_PREFIX)));
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test void cooldownAppliesAcrossFreshBrowserSessionsAndAllowsNextRequestAtBoundary() throws Exception {
        User customer = verifiedCustomer(); request(customer);
        request(customer);
        clock.instant = clock.instant.plusSeconds(59); request(customer);
        assertEquals(1, resetMessages(customer).size());
        clock.instant = clock.instant.plusSeconds(1); request(customer);
        assertEquals(2, resetMessages(customer).size());
        verify(sender, times(3)).send(any(MimeMessage.class));
    }

    @Test void passwordChangeRevokesOtherOutstandingResetLinks() throws Exception {
        User customer = verifiedCustomer(); request(customer);
        String first = token(latest(customer, AccountEmailService.RECOVERY_PREFIX));
        clock.instant = clock.instant.plusSeconds(60); request(customer);
        String second = token(latest(customer, AccountEmailService.RECOVERY_PREFIX));
        assertNotEquals(first, second);
        mvc.perform(post("/auth/reset-password").with(csrf()).param("token", first)
                        .param("password", REPLACEMENT).param("confirmPassword", REPLACEMENT))
                .andExpect(redirectedUrl("/auth/login"));
        mvc.perform(post("/auth/reset-password").with(csrf()).param("token", second)
                        .param("password", "Another-valid-password!").param("confirmPassword", "Another-valid-password!"))
                .andExpect(view().name("auth/reset-password")).andExpect(model().attributeExists("errorMessage"));
        assertTrue(encoder.matches(REPLACEMENT, users.findById(customer.getId()).orElseThrow().getPassword()));
    }

    @Test void concurrentRecoveryRequestsFromDifferentSessionsCreateOneResetAndOneEmail() throws Exception {
        User customer = verifiedCustomer();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> attempt = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent recovery did not start");
                request(customer); return null;
            };
            var first = pool.submit(attempt); var second = pool.submit(attempt);
            start.countDown(); first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
            assertEquals(1, resetMessages(customer).size());
            verify(sender, times(2)).send(any(MimeMessage.class));
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    User verifiedCustomer() {
        User customer = accounts.registerCustomer("Isolated recovery", UUID.randomUUID() + "@example.invalid", INITIAL, null, null);
        accounts.verifyEmail(token(latest(customer, AccountEmailService.VERIFICATION_PREFIX)));
        return customer;
    }
    void request(User customer) throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(post("/auth/forgot-password").with(csrf()).param("email", customer.getEmail()))
                .andExpect(redirectedUrl("/auth/forgot-password"))
                .andExpect(flash().attributeExists("successMessage"));
    }
    MockHttpSession signIn(String address, String password) throws Exception {
        SecurityContextHolder.clearContext();
        var result = mvc.perform(post("/auth/login").with(csrf()).param("email", address).param("password", password))
                .andExpect(redirectedUrl("/")).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session); SecurityContextHolder.clearContext(); return session;
    }
    List<OutboundEmail> resetMessages(User user) { return emails.findAll().stream().filter(e -> user.getEmail().equals(e.getRecipient()) && e.getEventKey().startsWith(AccountEmailService.RECOVERY_PREFIX)).toList(); }
    OutboundEmail latest(User user, String prefix) { return emails.findAll().stream().filter(e -> user.getEmail().equals(e.getRecipient()) && e.getEventKey().startsWith(prefix)).max(Comparator.comparing(OutboundEmail::getId)).orElseThrow(); }
    static String token(OutboundEmail email) { return email.getHtmlBody().split("token=")[1].split("[\"&\\s]", 2)[0]; }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
