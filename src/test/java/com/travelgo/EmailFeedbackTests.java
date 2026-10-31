package com.travelgo;

import com.travelgo.config.EmailFeedbackConfiguration;
import com.travelgo.entity.OutboundEmail;
import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.service.EmailFeedbackService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:email-feedback-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false",
        "travelgo.mail.feedback.brevo-enabled=true", "travelgo.mail.feedback.brevo-token=local_test_token_32_characters_123456789"})
@ActiveProfiles("test")
@Import({TestDatabaseSafetyConfiguration.class, WorkflowIntegrationTests.Time.class})
class EmailFeedbackTests {
    static final String TOKEN = "local_test_token_32_characters_123456789";
    static final String AUTH = "Bearer " + TOKEN;
    static final String URL = "/webhooks/email/brevo";
    @Autowired OutboundEmailRepository emails;
    @Autowired EmailFeedbackService feedback;
    @Autowired com.travelgo.repository.UserRepository users;
    @Autowired com.travelgo.repository.RoleRepository roles;
    @Autowired com.travelgo.repository.PermissionRepository permissions;
    @Autowired WorkflowIntegrationTests.MutableClock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    OutboundEmail email;
    @BeforeEach void fixture() {
        clock.instant = Instant.parse("2030-01-01T12:00:00Z");
        email = new TransactionTemplate(manager).execute(status -> {
            OutboundEmail row = new OutboundEmail(); row.setEventKey("booking:feedback:" + UUID.randomUUID());
            row.setRecipient("recipient@example.invalid"); row.setSubject("Synthetic callback verification");
            row.setHtmlBody("Private content must not enter callback logs or responses.");
            row.setStatus("SENT"); row.setCreatedAt(clock.instant().minusSeconds(600));
            row.setSentAt(clock.instant().minusSeconds(60)); row.setNextAttemptAt(clock.instant()); row.setAttempts(1);
            byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
            row.setFeedbackReference(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            return emails.saveAndFlush(row);
        });
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    OutboundEmail stored() { return emails.findById(email.getId()).orElseThrow(); }
    String body(String event, Instant at) {
        return "{\"event\":\"" + event + "\",\"email\":\"recipient@example.invalid\",\"message-id\":\"provider-message-123\","
                + "\"ts_event\":" + at.getEpochSecond() + ",\"X-Mailin-custom\":\"travelgo:" + email.getFeedbackReference() + "\"}";
    }
    void receive(String event, Instant at) { feedback.receive(AUTH, body(event, at).getBytes(StandardCharsets.UTF_8)); }
    @Test void callbackNeedsItsOwnAuthenticationAndCannotUseAQueryToken() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("delivered", clock.instant())))
            .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mvc.perform(post(URL).header("Authorization", "Bearer wrong").contentType(MediaType.APPLICATION_JSON).content(body("delivered", clock.instant())))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(URL).param("token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(body("delivered", clock.instant())))
            .andExpect(status().isUnauthorized());
        assertNull(stored().getRecipientDeliveryStatus());
    }
    @Test void exactPostCallbackWorksWithoutCsrfButOtherMutationsRemainProtected() throws Exception {
        mvc.perform(post(URL).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(body("delivered", clock.instant())))
            .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertEquals("DELIVERED", stored().getRecipientDeliveryStatus());
        mvc.perform(post("/admin/email-delivery/" + email.getId() + "/review").header("Authorization", AUTH))
            .andExpect(status().isForbidden());
        mvc.perform(post(URL + "/extra").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void feedbackNeverRequeuesCreatesMessagesOrChangesPaidBusinessData() {
        long count = emails.count(); Instant sentAt = email.getSentAt();
        receive("hard_bounce", clock.instant());
        assertEquals("HARD_BOUNCE", stored().getRecipientDeliveryStatus()); assertEquals("SENT", stored().getStatus());
        assertEquals(sentAt, stored().getSentAt()); assertEquals(1, stored().getAttempts()); assertEquals(count, emails.count());
    }
    @Test void duplicateAndConcurrentEventsProduceOneStableOutcome() throws Exception {
        byte[] payload = body("delivered", clock.instant()).getBytes(StandardCharsets.UTF_8);
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Void> call = () -> { start.await(); feedback.receive(AUTH, payload); return null; };
            Future<Void> first = executor.submit(call), second = executor.submit(call); start.countDown();
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        Instant receivedAt = stored().getRecipientFeedbackReceivedAt(); clock.instant = clock.instant.plusSeconds(60);
        feedback.receive(AUTH, payload);
        assertEquals(receivedAt, stored().getRecipientFeedbackReceivedAt()); assertEquals(1, stored().getAttempts());
        assertEquals("DELIVERED", stored().getRecipientDeliveryStatus());
    }
    @Test void staleTemporaryAndContradictorySuccessCannotUndoFinalFailure() {
        receive("deferred", clock.instant().minusSeconds(30));
        receive("delivered", clock.instant().minusSeconds(20));
        receive("soft_bounce", clock.instant().minusSeconds(10)); assertEquals("DELIVERED", stored().getRecipientDeliveryStatus());
        receive("hard_bounce", clock.instant());
        receive("delivered", clock.instant().plusSeconds(1));
        receive("deferred", clock.instant().minusSeconds(40));
        assertEquals("HARD_BOUNCE", stored().getRecipientDeliveryStatus());
    }
    @Test void authenticatedResultSettlesAnAmbiguousTransportAndPreventsRetry() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            OutboundEmail row = emails.lock(email.getId()).orElseThrow(); row.setStatus("REVIEW_REQUIRED"); row.setSentAt(null);
        });
        receive("delivered", clock.instant());
        assertEquals("SENT", stored().getStatus()); assertEquals(clock.instant(), stored().getSentAt());
        assertEquals("DELIVERED", stored().getRecipientDeliveryStatus());
    }
    @Test void differentRecipientAndUnknownReferenceAreIgnoredButConflictingProviderMessageRequiresReview() {
        feedback.receive(AUTH, body("delivered", clock.instant()).replace("recipient@example.invalid", "other@example.invalid").getBytes(StandardCharsets.UTF_8));
        assertNull(stored().getRecipientDeliveryStatus());
        feedback.receive(AUTH, body("delivered", clock.instant()).replace(email.getFeedbackReference(), "x".repeat(43)).getBytes(StandardCharsets.UTF_8));
        assertNull(stored().getRecipientDeliveryStatus());
        receive("delivered", clock.instant());
        feedback.receive(AUTH, body("hard_bounce", clock.instant().plusSeconds(1)).replace("provider-message-123", "other-message").getBytes(StandardCharsets.UTF_8));
        assertEquals("DELIVERED", stored().getRecipientDeliveryStatus());
        assertEquals("provider-message-123", stored().getProviderMessageId()); assertEquals("REVIEW_REQUIRED_CONFLICT", stored().getStatus());
        receive("spam", clock.instant().plusSeconds(2));
        assertEquals("SPAM", stored().getRecipientDeliveryStatus()); assertEquals("REVIEW_REQUIRED_CONFLICT", stored().getStatus());
    }

    @Test void matchingAndConflictingCallbacksReturnTheSameEmptyAcknowledgement() throws Exception {
        mvc.perform(post(URL).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(body("delivered", clock.instant())))
            .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(post(URL).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content(body("hard_bounce", clock.instant().plusSeconds(1)).replace("provider-message-123", "different-provider-message")))
            .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertEquals("REVIEW_REQUIRED_CONFLICT", stored().getStatus());
        assertEquals("provider-message-123", stored().getProviderMessageId()); assertEquals("DELIVERED", stored().getRecipientDeliveryStatus());
    }
    @Test void rawRowIdentifierCannotCorrelateAndInvalidPayloadDoesNotLeakContents() throws Exception {
        for (String malformed : java.util.List.of("[]", "{}", body("delivered", clock.instant()).replace("travelgo:" + email.getFeedbackReference(), "travelgo:" + email.getId()),
                body("delivered", clock.instant()).replace("recipient@example.invalid", "invalid"),
                body("delivered", clock.instant()).replace("provider-message-123", "unsafe\\nmessage"),
                body("delivered", clock.instant()) + " {}",
                body("delivered", clock.instant()).replace("{", "{\"event\":\"hard_bounce\","),
                body("delivered", clock.instant().plusSeconds(301)),
                body("delivered", clock.instant()).replace("\"ts_event\":" + clock.instant().getEpochSecond(), "\"ts_event\":1.2"))) {
            mvc.perform(post(URL).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(malformed))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        }
        assertNull(stored().getRecipientDeliveryStatus());
    }
    @Test void oversizedAndDeepPayloadsAreBounded() throws Exception {
        mvc.perform(post(URL).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content("x".repeat(16385)))
            .andExpect(status().isPayloadTooLarge());
        String deep = "{\"event\":\"delivered\",\"nested\":" + "[".repeat(15) + "0" + "]".repeat(15) + "}";
        mvc.perform(post(URL).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(deep))
            .andExpect(status().isBadRequest());
        assertNull(stored().getRecipientDeliveryStatus());
    }
    @Test void invalidEmailEventUsesTheDocumentedProviderNameAndOpenTrackingIsIgnored() {
        receive("opened", clock.instant()); assertNull(stored().getRecipientDeliveryStatus());
        receive("invalid_email", clock.instant()); assertEquals("INVALID", stored().getRecipientDeliveryStatus());
    }
    @Test void oldAndUnclaimedMessagesDoNotGainInventedDeliveryHistory() {
        receive("delivered", email.getCreatedAt().minusSeconds(301)); assertNull(stored().getRecipientDeliveryStatus());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var row = emails.lock(email.getId()).orElseThrow(); row.setStatus("QUEUED");
            row.setAttempts(0); row.setFeedbackReference(null);
        });
        receive("delivered", clock.instant()); assertNull(stored().getRecipientDeliveryStatus()); assertEquals("QUEUED", stored().getStatus());
    }
    @Test void delayedAcceptanceSettlesARequeuedPreviousClaimBeforeAnotherSend() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var row = emails.lock(email.getId()).orElseThrow(); row.setStatus("QUEUED"); row.setSentAt(null);
        });
        receive("delivered", clock.instant());
        assertEquals("SENT", stored().getStatus()); assertEquals("DELIVERED", stored().getRecipientDeliveryStatus());
        assertEquals(1, stored().getAttempts()); assertEquals(clock.instant(), stored().getSentAt());
        assertTrue(emails.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderById("QUEUED", clock.instant()).stream()
                .noneMatch(row -> row.getId().equals(email.getId())));
    }
    @Test void rotatingAClaimReferencePreventsAnOlderAttemptFromChangingTheNewClaim() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var row = emails.lock(email.getId()).orElseThrow(); row.setStatus("SENDING");
            row.setFeedbackReference("z".repeat(43)); row.setAttempts(2); row.setSentAt(null);
        });
        receive("delivered", clock.instant());
        assertEquals("SENDING", stored().getStatus()); assertNull(stored().getRecipientDeliveryStatus());
        assertNull(stored().getSentAt()); assertEquals(2, stored().getAttempts());
    }
    @Test void aPreviouslySuppressedAttemptCanReportDeliveryWithoutUnsuppressingOrRestoringContents() {
        long count = emails.count();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var row = emails.lock(email.getId()).orElseThrow(); row.setStatus("SUPPRESSED_ADMIN"); row.setSentAt(null);
            row.setHtmlBody("This message was suppressed before further delivery.");
            row.setAttachmentData(null); row.setAttachmentName(null);
        });
        receive("delivered", clock.instant());
        assertEquals("SUPPRESSED_ADMIN", stored().getStatus()); assertEquals("DELIVERED", stored().getRecipientDeliveryStatus());
        assertEquals("This message was suppressed before further delivery.", stored().getHtmlBody());
        assertNull(stored().getAttachmentData()); assertEquals(1, stored().getAttempts()); assertEquals(count, emails.count());
    }
    @Test void administratorCanSeeTheSeparateRecipientOutcomeWithoutCorrelationSecrets() throws Exception {
        receive("delivered", clock.instant());
        var administrator = new TransactionTemplate(manager).execute(status -> {
            var role = roles.findByRoleName("ADMIN").orElseGet(() -> roles.save(new com.travelgo.entity.Role("ADMIN", "Administrator")));
            var permission = permissions.findByPermissionName("SYSTEM_SETTINGS_VIEW").orElseGet(() -> permissions.save(
                    new com.travelgo.entity.Permission("SYSTEM_SETTINGS_VIEW", "View settings", "SYSTEM")));
            role.setPermissions(new java.util.HashSet<>(java.util.Set.of(permission))); roles.saveAndFlush(role);
            return users.saveAndFlush(new com.travelgo.entity.User("Synthetic feedback administrator", UUID.randomUUID() + "@example.invalid", "unused", role));
        });
        String html = mvc.perform(get("/admin/email-delivery").param("status", "SENT")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(administrator.getEmail())
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_SYSTEM_SETTINGS_VIEW"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Recipient delivery")); assertTrue(html.contains("Provider event time"));
        assertTrue(html.contains("Recipient server accepted")); assertTrue(html.contains("Transport accepted"));
        assertFalse(html.contains(email.getFeedbackReference())); assertTrue(html.contains("Provider message ID"));
        assertTrue(html.contains("provider-message-123")); assertTrue(html.contains("TG-MAIL-" + email.getId()));
        assertFalse(html.contains("Private content must not enter callback logs or responses."));
    }
    @Test void disabledEndpointReturnsNotFoundBeforeParsingOrCallingTheFeedbackService() throws Exception {
        var disabled = new EmailFeedbackConfiguration(false, "", "http://localhost:8081");
        var unusedService = org.mockito.Mockito.mock(EmailFeedbackService.class);
        var closedMvc = MockMvcBuilders.standaloneSetup(new com.travelgo.controller.EmailFeedbackController(disabled, unusedService))
                .setControllerAdvice(new com.travelgo.exception.GlobalExceptionHandler()).build();
        closedMvc.perform(post(URL).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content("invalid payload"))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        org.mockito.Mockito.verifyNoInteractions(unusedService);
    }
    @Test void disabledAdapterFailsClosedAndUnsafeOptInConfigurationFailsBeforeStartup() {
        assertEquals(404, assertThrows(ResponseStatusException.class,
            () -> new EmailFeedbackConfiguration(false, "", "http://localhost:8081").authenticate(AUTH)).getStatusCode().value());
        assertThrows(IllegalStateException.class, () -> new EmailFeedbackConfiguration(true, "", "http://localhost:8081"));
        assertThrows(IllegalStateException.class, () -> new EmailFeedbackConfiguration(true, TOKEN, "http://public.example"));
        assertThrows(IllegalStateException.class, () -> new EmailFeedbackConfiguration(true, TOKEN, "https://public.example/path"));
        assertDoesNotThrow(() -> new EmailFeedbackConfiguration(true, TOKEN, "https://agency.example").authenticate(AUTH));
    }
}
