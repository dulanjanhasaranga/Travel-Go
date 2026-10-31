package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:email-review-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false"})
@ActiveProfiles("test")
@Import({TestDatabaseSafetyConfiguration.class, WorkflowIntegrationTests.Time.class})
class EmailReviewTests {
    @Autowired EmailReviewService review;
    @Autowired OutboundEmailRepository emails;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired AuditLogRepository auditLogs;
    @Autowired AccountEmailService accountEmails;
    @Autowired PasswordResetRepository resets;
    @Autowired WorkflowIntegrationTests.MutableClock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired WebApplicationContext context;
    @Autowired org.thymeleaf.TemplateEngine templates;
    @MockitoSpyBean AdminAccess access;
    TransactionTemplate tx;
    Role adminRole;
    User admin;
    MockMvc mvc;

    @BeforeEach void fixture() {
        clock.instant = Instant.parse("2030-01-01T12:00:00Z");
        tx = new TransactionTemplate(manager);
        admin = tx.execute(status -> {
            adminRole = roles.findByRoleName("ADMIN").orElseGet(() -> roles.save(new Role("ADMIN", "Administrator")));
            Set<Permission> assigned = new HashSet<>();
            for (String name : List.of("SYSTEM_SETTINGS_VIEW", "SYSTEM_SETTINGS_MANAGE"))
                assigned.add(permissions.findByPermissionName(name).orElseGet(() -> permissions.save(new Permission(name, name, "SYSTEM"))));
            adminRole.setPermissions(assigned); roles.saveAndFlush(adminRole);
            return users.saveAndFlush(new User("Synthetic email administrator", UUID.randomUUID() + "@example.invalid", "unused", adminRole));
        });
        login(admin.getEmail(), "ADMIN");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }
    Long message(String status) {
        return tx.execute(transaction -> {
            OutboundEmail email = new OutboundEmail();
            email.setEventKey("booking:review-test:" + UUID.randomUUID()); email.setRecipient("fictional@example.invalid");
            email.setSubject("Synthetic booking notification"); email.setHtmlBody("<a href='https://example.invalid?token=secret-bearer'>Private body</a>");
            email.setAttachmentName("hidden-receipt.pdf"); email.setAttachmentData(new byte[]{1,2,3});
            email.setStatus(status); email.setCreatedAt(clock.instant()); email.setNextAttemptAt(clock.instant());
            email.setAttempts("QUEUED".equals(status) ? 0 : 1);
            return emails.saveAndFlush(email).getId();
        });
    }
    OutboundEmail stored(Long id) { return emails.findById(id).orElseThrow(); }
    List<AuditLog> audits(Long id) { return auditLogs.findAll().stream().filter(e -> "EMAIL_DELIVERY_REVIEW".equals(e.getAction()) && id.equals(e.getEntityId())).toList(); }
    static void login(String email, String role) { SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(email, "unused", WorkflowIntegrationTests.authorities(role))); }

    @Test void queuedSuppressionCommitsAuditAndErasesContentsWithoutCreatingAnotherMessage() {
        Long id = message("QUEUED"); long count = emails.count();
        assertEquals("SUPPRESSED_ADMIN", review.review(id, "SUPPRESS", "", "Demonstration recipient; do not deliver.", false));
        assertEquals("SUPPRESSED_ADMIN", stored(id).getStatus()); assertFalse(stored(id).getHtmlBody().contains("secret-bearer"));
        assertNull(stored(id).getAttachmentData()); assertNull(stored(id).getAttachmentName()); assertEquals(count, emails.count());
        assertEquals(admin.getEmail(), audits(id).get(0).getUsername());
        review.review(id, "SUPPRESS", "", "Same suppression request repeated.", false); assertEquals(1, audits(id).size());
    }

    @Test void acceptanceRequiresAttestationAndReferenceAndDoesNotPretendToDeliverOrResend() {
        Long id = message("REVIEW_REQUIRED");
        assertThrows(IllegalArgumentException.class, () -> review.review(id, "PROVIDER_ACCEPTED", "provider-log-123", "Provider recorded acceptance.", false));
        assertThrows(IllegalArgumentException.class, () -> review.review(id, "PROVIDER_ACCEPTED", "", "Provider recorded acceptance.", true));
        assertEquals("REVIEW_REQUIRED", stored(id).getStatus()); assertTrue(audits(id).isEmpty());
        review.review(id, "PROVIDER_ACCEPTED", "provider-log-123", "Provider recorded acceptance.", true);
        assertEquals("SENT", stored(id).getStatus()); assertEquals(clock.instant(), stored(id).getSentAt()); assertEquals(1, stored(id).getAttempts());
        review.review(id, "PROVIDER_ACCEPTED", "provider-log-123", "Same provider result repeated.", true); assertEquals(1, audits(id).size());
    }

    @Test void confirmedNonAcceptanceRequeuesOnlyUncertainMessagesAndRepeatsCreateOneAudit() {
        Long id = message("REVIEW_REQUIRED");
        assertThrows(IllegalArgumentException.class, () -> review.review(id, "REQUEUE_NOT_ACCEPTED", "provider-log-456", "Provider confirmed no acceptance.", false));
        review.review(id, "REQUEUE_NOT_ACCEPTED", "provider-log-456", "Provider confirmed no acceptance.", true);
        review.review(id, "REQUEUE_NOT_ACCEPTED", "provider-log-456", "Provider confirmed no acceptance.", true);
        assertEquals("QUEUED", stored(id).getStatus()); assertEquals(clock.instant(), stored(id).getNextAttemptAt());
        assertEquals(1, stored(id).getAttempts()); assertEquals(1, audits(id).size());
        assertTrue(audits(id).get(0).getDetails().contains("provider-log-456"));
    }

    @Test void concurrentRequeueRequestsSerializeTheOutcomeAndItsAudit() throws Exception {
        Long id = message("REVIEW_REQUIRED"); ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<String> action = () -> { login(admin.getEmail(), "ADMIN"); try { start.await(); return review.review(id,
                    "REQUEUE_NOT_ACCEPTED", "provider-case-789", "Provider confirmed rejection before acceptance.", true); }
                finally { SecurityContextHolder.clearContext(); } };
            Future<String> first = executor.submit(action), second = executor.submit(action); start.countDown();
            assertEquals("QUEUED", first.get(15, TimeUnit.SECONDS)); assertEquals("QUEUED", second.get(15, TimeUnit.SECONDS));
            assertEquals(1, audits(id).size()); assertEquals(1, stored(id).getAttempts());
        } finally { executor.shutdownNow(); }
    }

    @Test void inFlightAndCompletedMessagesCannotBeSuppressedOrRetried() {
        for (String status : List.of("SENDING", "SENT", "SUPPRESSED_EXPIRED", "SUPPRESSED_REVOKED")) {
            Long id = message(status);
            assertThrows(IllegalStateException.class, () -> review.review(id, "SUPPRESS", "", "Invalid delivery suppression attempt.", false));
            assertThrows(IllegalStateException.class, () -> review.review(id, "REQUEUE_NOT_ACCEPTED", "provider-case", "Invalid delivery retry attempt.", true));
            assertEquals(status, stored(id).getStatus()); assertTrue(audits(id).isEmpty());
        }
    }

    @Test void expiredRecoveryLinkIsSuppressedRatherThanRequeued() {
        String address = UUID.randomUUID() + "@example.invalid";
        Long id = tx.execute(status -> {
            Role role = roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.save(new Role("CUSTOMER", "Customer")));
            User customer = users.saveAndFlush(new User("Synthetic recovery customer", address, "unused", role));
            String token = "synthetic-local-reset-token", hash = AccountEmailDurabilityTests.hash(token);
            PasswordReset reset = new PasswordReset(); reset.setTokenHash(hash); reset.setUser(customer);
            reset.setExpiresAt(clock.instant().plusSeconds(1800)); reset.setPasswordAtIssue(customer.getPassword()); resets.saveAndFlush(reset);
            accountEmails.recovery(customer, token, hash);
            OutboundEmail email = emails.findAll().stream().filter(e -> address.equals(e.getRecipient())).findFirst().orElseThrow();
            email.setStatus("REVIEW_REQUIRED"); return email.getId();
        });
        clock.instant = clock.instant.plusSeconds(1800);
        assertEquals("SUPPRESSED_EXPIRED", review.review(id, "REQUEUE_NOT_ACCEPTED", "provider-case-999", "Provider confirmed no acceptance.", true));
        assertFalse(stored(id).getHtmlBody().contains("token=")); assertEquals(1, audits(id).size());
    }

    @Test void auditFailureRollsBackSuppressionAndPreservesOriginalQueueContents() {
        Long id = message("QUEUED");
        doThrow(new IllegalStateException("Synthetic audit failure")).when(access).record(any(User.class),
                eq("EMAIL_DELIVERY_REVIEW"), eq("OutboundEmail"), eq(id), anyString());
        assertThrows(IllegalStateException.class, () -> review.review(id, "SUPPRESS", "", "Do not deliver to this fixture.", false));
        assertEquals("QUEUED", stored(id).getStatus()); assertTrue(stored(id).getHtmlBody().contains("secret-bearer"));
        assertNotNull(stored(id).getAttachmentData()); assertTrue(audits(id).isEmpty());
    }

    @Test void storedPermissionsAreCheckedEvenWhenTheAuthenticationHasOldAdministratorAuthorities() {
        Long id = message("QUEUED");
        tx.executeWithoutResult(status -> { Role role = roles.findById(adminRole.getId()).orElseThrow(); role.getPermissions().clear(); roles.flush(); });
        assertThrows(AccessDeniedException.class, () -> review.findPage("QUEUED", 0));
        assertThrows(AccessDeniedException.class, () -> review.review(id, "SUPPRESS", "", "Do not deliver to this fixture.", false));
        assertEquals("QUEUED", stored(id).getStatus()); assertTrue(audits(id).isEmpty());
    }

    @Test void aViewOnlyAdministratorCanReadButCannotReviewAndAnInactiveAdministratorCannotRead() {
        Long id = message("QUEUED");
        tx.executeWithoutResult(status -> {
            Role role = roles.findById(adminRole.getId()).orElseThrow();
            role.getPermissions().clear(); role.getPermissions().add(permissions.findByPermissionName("SYSTEM_SETTINGS_VIEW").orElseThrow()); roles.flush();
        });
        assertFalse(review.findPage("QUEUED", 0).isEmpty());
        assertThrows(AccessDeniedException.class, () -> review.review(id, "SUPPRESS", "", "View-only administrator attempted suppression.", false));
        tx.executeWithoutResult(status -> { User current = users.findById(admin.getId()).orElseThrow(); current.setActive(false); users.saveAndFlush(current); });
        assertThrows(AccessDeniedException.class, () -> review.findPage("QUEUED", 0));
        assertEquals("QUEUED", stored(id).getStatus()); assertTrue(audits(id).isEmpty());
    }

    @Test void boundedMetadataPageNeverDisplaysAccountLinksBodiesOrAttachments() throws Exception {
        Long id = message("REVIEW_REQUIRED");
        tx.executeWithoutResult(transaction -> emails.lock(id).orElseThrow().setProviderMessageId("<provider-review-id@relay.test>"));
        message("REVIEW_REQUIRED"); // An uncertain send can have no provider receipt.
        String html = mvc.perform(get("/admin/email-delivery").with(user(admin.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))))
                .andExpect(status().isOk()).andExpect(view().name("admin/email-delivery")).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("fictional@example.invalid")); assertTrue(html.contains("TG-MAIL-" + id));
        assertTrue(html.contains("Provider message ID")); assertTrue(html.contains("provider-review-id@relay.test"));
        assertTrue(html.matches("(?s).*<dt>Provider message ID</dt><dd[^>]*>Not reported</dd>.*"));
        assertFalse(html.contains("travelgo-" + id + "@notifications.travelgo"));
        assertFalse(html.contains("secret-bearer")); assertFalse(html.contains("Private body")); assertFalse(html.contains("hidden-receipt.pdf"));
        // MockMvc restores/clears its request security context; direct service assertions need their own actor.
        login(admin.getEmail(), "ADMIN");
        assertEquals(20, review.findPage("", 0).getSize());
        assertThrows(IllegalArgumentException.class, () -> review.findPage("INVALID", 0));
        assertThrows(IllegalArgumentException.class, () -> review.findPage("", -1));
    }

    @Test void defaultReviewMetadataIncludesConflictsAndExplicitConflictFilterIsExactWithoutEntityLoading() {
        Long uncertain = message("REVIEW_REQUIRED"), conflict = message("REVIEW_REQUIRED_CONFLICT"), accepted = message("SENT");
        tx.executeWithoutResult(transaction -> {
            var row = emails.lock(conflict).orElseThrow(); row.setRecipientFeedbackReceivedAt(clock.instant());
            row.setProviderMessageId("provider-conflict-id@relay.test");
        });
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true); statistics.clear();
        try {
            var page = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));
            var defaultReview = emails.findDeliveryMetadata("REVIEW_REQUIRED", page);
            var reviewIds = defaultReview.stream().map(OutboundEmailRepository.DeliveryMetadata::getId).toList();
            assertTrue(reviewIds.containsAll(List.of(uncertain, conflict))); assertFalse(reviewIds.contains(accepted));
            assertTrue(defaultReview.stream().allMatch(row -> Set.of("REVIEW_REQUIRED", "REVIEW_REQUIRED_CONFLICT").contains(row.getStatus())));
            var conflicts = emails.findDeliveryMetadata("REVIEW_REQUIRED_CONFLICT", page);
            assertTrue(conflicts.stream().anyMatch(row -> conflict.equals(row.getId()) && clock.instant().equals(row.getRecipientFeedbackReceivedAt())));
            assertTrue(conflicts.stream().anyMatch(row -> conflict.equals(row.getId()) && "provider-conflict-id@relay.test".equals(row.getProviderMessageId())));
            assertTrue(conflicts.stream().allMatch(row -> "REVIEW_REQUIRED_CONFLICT".equals(row.getStatus())));
            assertEquals(0, statistics.getEntityLoadCount(), "Administrative metadata queries must not hydrate outbox entities containing bearer links or attachments.");
        } finally { statistics.setStatisticsEnabled(previouslyEnabled); }
        var conflictSummary = review.findPage("REVIEW_REQUIRED", 0).stream().filter(row -> conflict.equals(row.id())).findFirst().orElseThrow();
        assertTrue(conflictSummary.uncertain()); assertNull(conflictSummary.recipientDeliveryStatus()); assertFalse(conflictSummary.retryable());
        assertEquals("provider-conflict-id@relay.test", conflictSummary.messageId()); assertEquals("TG-MAIL-" + conflict, conflictSummary.travelGoReference());
    }

    @Test void mvcRequiresAdministratorPermissionCsrfAndEvidence() throws Exception {
        Long id = message("REVIEW_REQUIRED");
        SecurityContextHolder.clearContext();
        mvc.perform(get("/admin/email-delivery")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/email-delivery").with(user("unknown@example.invalid").roles("CUSTOMER"))).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/email-delivery/" + id + "/review").with(user(admin.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN")))
                .param("action", "SUPPRESS").param("note", "Suppression fixture without CSRF."))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/email-delivery/" + id + "/review").with(user(admin.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))).with(csrf())
                .param("action", "REQUEUE_NOT_ACCEPTED").param("note", "No evidence has been provided."))
                .andExpect(redirectedUrl("/admin/email-delivery")).andExpect(flash().attributeExists("errorMessage"));
        assertEquals("REVIEW_REQUIRED", stored(id).getStatus()); assertTrue(audits(id).isEmpty());
        mvc.perform(post("/admin/email-delivery/" + id + "/review").with(user(admin.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))).with(csrf())
                .param("action", "PROVIDER_ACCEPTED").param("evidenceReference", "fixture-provider-123").param("providerChecked", "true")
                .param("note", "Provider record confirms acceptance."))
                .andExpect(redirectedUrl("/admin/email-delivery")).andExpect(flash().attributeExists("successMessage"));
        assertEquals("SENT", stored(id).getStatus());
    }

    @Test void inquiryEmailShowsItsReplyWithoutIrrelevantBookingVisaOrPaymentInstructions() {
        org.thymeleaf.context.Context context = new org.thymeleaf.context.Context();
        context.setVariables(Map.of("heading", "Travel inquiry reply", "customerName", "Synthetic customer",
                "intro", "Our team has replied.", "details", Map.of("Reply", "<script>untrusted reply</script>"),
                "nextStep", "Contact the travel team for further advice.", "contact", "fictional@example.invalid",
                "simulatedPayments", true));
        String html = templates.process("email/transaction", context);
        assertTrue(html.contains("Contact the travel team for further advice."));
        assertFalse(html.contains("View booking details")); assertFalse(html.contains("visa decision"));
        assertFalse(html.contains("Payments here are simulated")); assertFalse(html.contains("<script>untrusted reply</script>"));
        assertTrue(html.contains("&lt;script&gt;untrusted reply&lt;/script&gt;"));
        context.setVariable("link", "http://127.0.0.1:8080/customer/bookings/123");
        String booking = templates.process("email/transaction", context);
        assertTrue(booking.contains("View booking details")); assertTrue(booking.contains("Payments here are simulated"));
    }
}
