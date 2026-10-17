package com.travelgo;

import com.travelgo.config.AccountEmailDispatcher;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.import=", "travelgo.mail.enabled=true",
        "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false"})
@ActiveProfiles("test")
@Import({WorkflowIntegrationTests.Time.class, TestDatabaseSafetyConfiguration.class})
class AccountEmailDurabilityTests {
    @Autowired UserService accounts;
    @Autowired AccountRecoveryService recovery;
    @Autowired EmailDeliveryService delivery;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired EmailVerificationTokenRepository verification;
    @Autowired PasswordResetRepository resets;
    @Autowired OutboundEmailRepository emails;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager manager;
    @Autowired WorkflowIntegrationTests.MutableClock clock;
    @Autowired WebApplicationContext context;
    @MockitoBean JavaMailSender sender;
    // Keep token/transaction assertions deterministic; dedicated executor behavior has isolated scheduling tests.
    @MockitoBean AccountEmailDispatchService accountDispatch;
    TransactionTemplate tx;
    User customer;

    @BeforeEach void fixture() {
        clock.instant = Instant.parse("2030-01-01T12:00:00Z");
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        customer = tx.execute(status -> {
            Role role = roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.save(new Role("CUSTOMER", "Customer")));
            return users.save(new User("Account mail test", UUID.randomUUID() + "@example.invalid",
                    encoder.encode("Initial-password!"), role));
        });
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        when(accountDispatch.isConfigured()).thenAnswer(call -> delivery.isConfigured());
        when(accountDispatch.submit(anyLong())).thenAnswer(call -> { delivery.deliver(call.getArgument(0)); return true; });
    }

    @Test void verificationAndItsMessageCommitBeforeSmtpWithGeneralDispatchPaused() throws Exception {
        String address = UUID.randomUUID() + "@example.invalid";
        AtomicReference<OutboundEmail> queued = new AtomicReference<>();
        doAnswer(call -> {
            MimeMessage message = call.getArgument(0);
            String token = token(html(message.getContent()));
            var fresh = new TransactionTemplate(manager);
            fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            fresh.executeWithoutResult(status -> {
                assertTrue(verification.findById(hash(token)).isPresent());
                assertFalse(users.findByEmailIgnoreCase(address).orElseThrow().isActive());
            });
            return null;
        }).when(sender).send(any(MimeMessage.class));
        tx.executeWithoutResult(status -> {
            accounts.registerCustomer("New account", address, "A-strong-password!", null, null);
            verify(sender, never()).createMimeMessage();
            queued.set(accountEmail(address, AccountEmailService.VERIFICATION_PREFIX));
            assertEquals("QUEUED", queued.get().getStatus());
            assertEquals(AccountEmailService.VERIFICATION_PREFIX + hash(token(queued.get().getHtmlBody())), queued.get().getEventKey());
        });
        assertEquals("SENT", emails.findById(queued.get().getId()).orElseThrow().getStatus());
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test void rolledBackRegistrationLeavesNeitherAccountTokenMessageNorSmtpSend() {
        String address = UUID.randomUUID() + "@example.invalid";
        AtomicReference<String> key = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            accounts.registerCustomer("Rolled back", address, "A-strong-password!", null, null);
            key.set(accountEmail(address, AccountEmailService.VERIFICATION_PREFIX).getEventKey());
            status.setRollbackOnly();
        });
        assertTrue(users.findByEmailIgnoreCase(address).isEmpty());
        assertTrue(emails.findByEventKey(key.get()).isEmpty());
        assertTrue(verification.findById(key.get().substring(AccountEmailService.VERIFICATION_PREFIX.length())).isEmpty());
        verify(sender, never()).createMimeMessage();
    }

    @Test void recoveryCommitSurvivesAmbiguousSmtpAndNeverAutomaticallyResends() {
        doThrow(new MailSendException("ambiguous transport result")).when(sender).send(any(MimeMessage.class));
        assertDoesNotThrow(() -> recovery.request(customer.getEmail()));
        OutboundEmail email = accountEmail(customer.getEmail(), AccountEmailService.RECOVERY_PREFIX);
        assertEquals("REVIEW_REQUIRED", email.getStatus());
        assertTrue(resets.findById(email.getEventKey().substring(AccountEmailService.RECOVERY_PREFIX.length())).isPresent());
        delivery.deliver(email.getId());
        assertEquals(1, emails.findById(email.getId()).orElseThrow().getAttempts());
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test void rolledBackRecoveryLeavesNoTokenMessageOrSend() {
        long before = resets.count();
        tx.executeWithoutResult(status -> { recovery.request(customer.getEmail()); status.setRollbackOnly(); });
        assertEquals(before, resets.count());
        assertTrue(emails.findAll().stream().noneMatch(e -> customer.getEmail().equals(e.getRecipient())));
        verify(sender, never()).createMimeMessage();
    }

    @Test void verificationExpiredBeforeClaimIsSuppressedAndBearerLinkErased() {
        String address = UUID.randomUUID() + "@example.invalid";
        tx.executeWithoutResult(status -> {
            accounts.registerCustomer("Expired link", address, "A-strong-password!", null, null);
            clock.instant = clock.instant.plusSeconds(86400);
        });
        OutboundEmail email = accountEmail(address, AccountEmailService.VERIFICATION_PREFIX);
        assertEquals("SUPPRESSED_EXPIRED", email.getStatus());
        assertFalse(email.getHtmlBody().contains("token="));
        assertEquals(0, email.getAttempts());
        verify(sender, never()).createMimeMessage();
    }

    @Test void verificationRevokedBeforeClaimIsSuppressed() {
        String address = UUID.randomUUID() + "@example.invalid";
        tx.executeWithoutResult(status -> {
            User user = accounts.registerCustomer("Revoked link", address, "A-strong-password!", null, null);
            verification.invalidateUnusedForUser(user.getId());
        });
        assertEquals("SUPPRESSED_REVOKED", accountEmail(address, AccountEmailService.VERIFICATION_PREFIX).getStatus());
        verify(sender, never()).createMimeMessage();
    }

    @Test void recoveryExpiredAtBoundaryIsSuppressed() {
        tx.executeWithoutResult(status -> {
            recovery.request(customer.getEmail()); clock.instant = clock.instant.plusSeconds(1800);
        });
        assertEquals("SUPPRESSED_EXPIRED", accountEmail(customer.getEmail(), AccountEmailService.RECOVERY_PREFIX).getStatus());
        verify(sender, never()).createMimeMessage();
    }

    @Test void accountDispatcherRecoversDueMessagesWithoutDrainingPausedBookingQueue() {
        tx.executeWithoutResult(status -> {
            recovery.request(customer.getEmail());
            accountEmail(customer.getEmail(), AccountEmailService.RECOVERY_PREFIX).setNextAttemptAt(clock.instant.plusSeconds(60));
            OutboundEmail booking = new OutboundEmail();
            booking.setEventKey("booking:test:" + UUID.randomUUID()); booking.setRecipient(customer.getEmail());
            booking.setSubject("Paused booking message"); booking.setHtmlBody("<p>Paused</p>");
            booking.setCreatedAt(clock.instant()); booking.setNextAttemptAt(clock.instant()); emails.save(booking);
        });
        OutboundEmail account = accountEmail(customer.getEmail(), AccountEmailService.RECOVERY_PREFIX);
        assertEquals("QUEUED", account.getStatus()); verify(sender, never()).createMimeMessage();
        clock.instant = clock.instant.plusSeconds(60);
        new AccountEmailDispatcher(emails, accountDispatch, clock).dispatch();
        assertEquals("SENT", emails.findById(account.getId()).orElseThrow().getStatus());
        assertTrue(emails.findAll().stream().anyMatch(e -> customer.getEmail().equals(e.getRecipient())
                && e.getEventKey().startsWith("booking:test:") && "QUEUED".equals(e.getStatus())));
    }

    @Test void passwordChangeSuppressesQueuedRecoveryBeforeEitherDispatcherCanSendIt() {
        tx.executeWithoutResult(status -> {
            recovery.request(customer.getEmail());
            accountEmail(customer.getEmail(), AccountEmailService.RECOVERY_PREFIX).setNextAttemptAt(clock.instant.plusSeconds(60));
            users.findById(customer.getId()).orElseThrow().setPassword(encoder.encode("Changed-password!"));
        });
        clock.instant = clock.instant.plusSeconds(60);
        OutboundEmail email = accountEmail(customer.getEmail(), AccountEmailService.RECOVERY_PREFIX);
        delivery.deliver(email.getId());
        assertEquals("SUPPRESSED_REVOKED", emails.findById(email.getId()).orElseThrow().getStatus());
        verify(sender, never()).createMimeMessage();
    }

    @Test void unknownRecoveryAddressDoesNotCreateAnEmailOrSend() {
        long before = emails.count(); recovery.request(UUID.randomUUID() + "@example.invalid");
        assertEquals(before, emails.count()); verify(sender, never()).createMimeMessage();
    }

    @Test void freshBrowserSessionsCannotBypassAccountRecoveryCooldown() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String neutral = "If this email address belongs to an active account, you will receive a reset link. Check your inbox and spam folder.";
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/auth/forgot-password").session(new MockHttpSession()).with(csrf())
                    .param("email", customer.getEmail()))
                    .andExpect(redirectedUrl("/auth/forgot-password"))
                    .andExpect(flash().attribute("successMessage", neutral));
        }
        assertEquals(1, recoveryTokenCount());
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test void recoveryCooldownAllowsNextRequestAtExactMinute() {
        recovery.request(customer.getEmail());
        clock.instant = clock.instant.plusSeconds(59);
        recovery.request(customer.getEmail());
        assertEquals(1, recoveryTokenCount());
        clock.instant = clock.instant.plusSeconds(1);
        recovery.request(customer.getEmail());
        assertEquals(2, recoveryTokenCount());
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test void simultaneousRecoveryRequestsCreateOneTokenAndEmail() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { ready.countDown(); start.await(); recovery.request(customer.getEmail()); return null; });
            var second = executor.submit(() -> { ready.countDown(); start.await(); recovery.request(customer.getEmail()); return null; });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertEquals(1, recoveryTokenCount());
            verify(sender, times(1)).send(any(MimeMessage.class));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    long recoveryTokenCount() {
        return resets.findAll().stream().filter(reset -> customer.getId().equals(reset.getUser().getId())).count();
    }

    OutboundEmail accountEmail(String address, String prefix) {
        return emails.findAll().stream().filter(e -> address.equals(e.getRecipient()) && e.getEventKey().startsWith(prefix))
                .findFirst().orElseThrow();
    }
    static String token(String body) { return body.split("token=")[1].split("[\"&\\s]", 2)[0]; }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    static String html(Object content) throws Exception {
        if (content instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                Object part = multipart.getBodyPart(index).getContent();
                if (part instanceof Multipart) { String nested = html(part); if (!nested.isEmpty()) return nested; }
                else if (multipart.getBodyPart(index).isMimeType("text/html")) return part.toString();
            }
        }
        return "";
    }
}
