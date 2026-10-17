package com.travelgo;

import com.travelgo.entity.OutboundEmail;
import com.travelgo.entity.Role;
import com.travelgo.entity.SystemSettings;
import com.travelgo.repository.EmailVerificationTokenRepository;
import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.SystemSettingsRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.service.AccountEmailService;
import com.travelgo.service.AccountEmailDispatchService;
import com.travelgo.service.EmailDeliveryService;
import com.travelgo.service.UserService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(properties = {"spring.config.import=", "travelgo.mail.enabled=true",
        "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false"})
@ActiveProfiles("test")
@Import({WorkflowIntegrationTests.Time.class, TestDatabaseSafetyConfiguration.class})
class RegistrationVerificationFlowTests {
    private static final String PASSWORD = "Registration-only-123!";
    @Autowired WebApplicationContext context;
    @Autowired RoleRepository roles;
    @Autowired SystemSettingsRepository settings;
    @Autowired UserRepository users;
    @Autowired EmailVerificationTokenRepository tokens;
    @Autowired OutboundEmailRepository outbox;
    @Autowired UserService accounts;
    @Autowired PlatformTransactionManager manager;
    @Autowired WorkflowIntegrationTests.MutableClock clock;
    @MockitoBean JavaMailSender sender;
    @MockitoBean AccountEmailDispatchService accountDispatch;
    @Autowired EmailDeliveryService delivery;
    MockMvc mvc;

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        clock.instant = Instant.parse("2030-01-01T12:00:00Z");
        roles.findByRoleName("ADMIN").orElseGet(() -> roles.saveAndFlush(new Role("ADMIN", "Isolated signup test")));
        roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.saveAndFlush(new Role("CUSTOMER", "Isolated signup test")));
        SystemSettings site = settings.findFirstByOrderByIdAsc().orElseGet(SystemSettings::new);
        site.setWebsiteStatus("ACTIVE");
        settings.saveAndFlush(site);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        // The executor itself is exercised separately; this fixture inspects committed token state in the mock transport.
        when(accountDispatch.submit(anyLong())).thenAnswer(call -> { delivery.deliver(call.getArgument(0)); return true; });
        assertTrue(accounts.isEmailVerificationConfigured());
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void configuredTransportOffersTheValidatedRegistrationForm() throws Exception {
        mvc.perform(get("/auth/register")).andExpect(status().isOk())
                .andExpect(model().attribute("registrationAvailable", true))
                .andExpect(content().string(containsString("action=\"/auth/register\"")))
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test void accountTokenAndEmailCommitBeforeTheFakeTransportReceivesTheMessage() throws Exception {
        String address = UUID.randomUUID() + "@example.invalid";
        var committed = new AtomicBoolean();
        doAnswer(call -> {
            var fresh = new TransactionTemplate(manager);
            fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            fresh.executeWithoutResult(status -> {
                var user = users.findByEmailIgnoreCase(address).orElseThrow();
                assertFalse(user.isActive());
                assertEquals("CUSTOMER", user.getRole().getRoleName());
                var email = verificationEmail(address);
                var token = tokens.findById(hash(token(email))).orElseThrow();
                assertEquals(user.getId(), token.getUser().getId());
                assertFalse(token.isUsed());
                assertEquals(clock.instant.plusSeconds(86400), token.getExpiresAt());
                committed.set(true);
            });
            return null;
        }).when(sender).send(any(MimeMessage.class));
        register(address);
        assertTrue(committed.get(), "The account, verification token and durable email must be committed before sending");
        assertEquals("SENT", verificationEmail(address).getStatus());
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test void customerCannotSignInUntilEmailVerificationAndLinkWorksOnlyOnce() throws Exception {
        String address = UUID.randomUUID() + "@example.invalid";
        register(address);
        var email = verificationEmail(address);
        String token = token(email);
        assertFalse(users.findByEmailIgnoreCase(address).orElseThrow().isActive());
        mvc.perform(post("/auth/login").with(csrf()).param("email", address).param("password", PASSWORD))
                .andExpect(redirectedUrl("/auth/login?error=true"));
        mvc.perform(get("/auth/verify-email").param("token", token))
                .andExpect(status().isOk()).andExpect(view().name("auth/verify-email"))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        assertFalse(users.findByEmailIgnoreCase(address).orElseThrow().isActive());
        assertFalse(tokens.findById(hash(token)).orElseThrow().isUsed());
        mvc.perform(post("/auth/verify-email").with(csrf()).param("token", token))
                .andExpect(redirectedUrl("/auth/login"))
                .andExpect(flash().attribute("successMessage", "Email verified. You can now sign in."));
        assertTrue(users.findByEmailIgnoreCase(address).orElseThrow().isActive());
        assertTrue(tokens.findById(hash(token)).orElseThrow().isUsed());
        mvc.perform(post("/auth/verify-email").with(csrf()).param("token", token))
                .andExpect(redirectedUrl("/auth/login"))
                .andExpect(flash().attribute("errorMessage", containsString("invalid or has expired")));
        mvc.perform(post("/auth/login").with(csrf()).param("email", address).param("password", PASSWORD))
                .andExpect(redirectedUrl("/"));
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test void rolledBackRegistrationLeavesNoAccountTokenEmailOrTransportCall() {
        String address = UUID.randomUUID() + "@example.invalid";
        long before = tokens.count();
        var tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            accounts.registerCustomer("Rollback signup", address, PASSWORD, null, null);
            assertTrue(users.findByEmailIgnoreCase(address).isPresent());
            assertNotNull(verificationEmail(address));
            status.setRollbackOnly();
        });
        assertTrue(users.findByEmailIgnoreCase(address).isEmpty());
        assertEquals(before, tokens.count());
        assertTrue(outbox.findAll().stream().noneMatch(email -> address.equals(email.getRecipient())));
        verify(sender, never()).createMimeMessage();
        verify(sender, never()).send(any(MimeMessage.class));
    }

    private void register(String address) throws Exception {
        mvc.perform(post("/auth/register").with(csrf()).param("name", "Registration test")
                        .param("email", address).param("password", PASSWORD).param("confirmPassword", PASSWORD))
                .andExpect(redirectedUrl("/auth/login"))
                .andExpect(flash().attribute("successMessage", containsString("verification link")));
    }

    private OutboundEmail verificationEmail(String address) {
        return outbox.findAll().stream().filter(email -> address.equals(email.getRecipient())
                && email.getEventKey().startsWith(AccountEmailService.VERIFICATION_PREFIX)).findFirst().orElseThrow();
    }

    private static String token(OutboundEmail email) {
        var match = Pattern.compile("/auth/verify-email\\?token=([A-Za-z0-9_-]+)").matcher(email.getHtmlBody());
        assertTrue(match.find(), "The isolated email must contain its verification route");
        return match.group(1);
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
