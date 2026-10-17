package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.enums.ContactMessageStatus;
import com.travelgo.repository.*;
import com.travelgo.service.ContactMessageService;
import org.junit.jupiter.api.*;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.import=", "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false", "spring.jpa.open-in-view=true"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
class InquiryReplyRevisionTests {
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired ContactMessageRepository inquiries;
    @Autowired OutboundEmailRepository emails;
    @Autowired NotificationRepository notifications;
    @Autowired ContactMessageService service;
    @Autowired PlatformTransactionManager manager;
    @Autowired WebApplicationContext context;
    User administrator, customer;
    ContactMessage inquiry;
    TransactionTemplate tx;

    @BeforeEach void fixture() {
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> {
            Role adminRole = roles.findByRoleName("ADMIN").orElseGet(() -> roles.save(new Role("ADMIN", "Administrator")));
            Role customerRole = roles.findByRoleName("CUSTOMER").orElseGet(() -> roles.save(new Role("CUSTOMER", "Customer")));
            administrator = users.save(new User("Synthetic administrator", UUID.randomUUID() + "@example.invalid", "unused-test-password", adminRole));
            customer = users.save(new User("Synthetic customer", UUID.randomUUID() + "@example.invalid", "unused-test-password", customerRole));
            inquiry = new ContactMessage(); inquiry.setSenderName(customer.getName()); inquiry.setSenderEmail(customer.getEmail());
            inquiry.setSubject("Reply revision test"); inquiry.setMessage("Please advise about my trip."); inquiry.setUser(customer);
            inquiries.saveAndFlush(inquiry);
        });
        login();
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void changedRepliesHaveSeparateQueuedEmailsAndIdenticalReplayAddsNothing() {
        Long firstVersion = inquiry.getVersion();
        ContactMessage first = service.replyToMessage(inquiry.getId(), "Initial advice", firstVersion);
        ContactMessage second = service.replyToMessage(inquiry.getId(), "Updated travel advice", first.getVersion());
        List<OutboundEmail> queued = queuedReplies();
        assertEquals(2, queued.size()); assertNotEquals(queued.get(0).getEventKey(), queued.get(1).getEventKey());
        assertTrue(queued.get(0).getHtmlBody().contains("Initial advice"));
        assertTrue(queued.get(1).getHtmlBody().contains("Updated travel advice"));
        assertTrue(queued.stream().allMatch(email -> "QUEUED".equals(email.getStatus()) && customer.getEmail().equals(email.getRecipient())));
        assertEquals(2, replyNotifications());
        service.replyToMessage(inquiry.getId(), "  Updated travel advice  ", firstVersion);
        assertEquals(second.getVersion(), inquiries.findById(inquiry.getId()).orElseThrow().getVersion());
        assertEquals(2, queuedReplies().size()); assertEquals(2, replyNotifications());
        assertEquals("Updated travel advice", inquiries.findById(inquiry.getId()).orElseThrow().getReplyText());
    }

    @Test void staleDifferentReplyDoesNotOverwriteTheLatestReplyOrCreateDeliveryRecords() {
        Long originalVersion = inquiry.getVersion();
        ContactMessage saved = service.replyToMessage(inquiry.getId(), "Saved current advice", originalVersion);
        assertThrows(IllegalStateException.class, () -> service.replyToMessage(inquiry.getId(), "Stale overwrite", originalVersion));
        assertThrows(IllegalStateException.class, () -> service.replyToMessage(inquiry.getId(), "Missing version overwrite", null));
        assertEquals(saved.getVersion(), inquiries.findById(inquiry.getId()).orElseThrow().getVersion());
        assertEquals("Saved current advice", inquiries.findById(inquiry.getId()).orElseThrow().getReplyText());
        assertEquals(1, queuedReplies().size()); assertEquals(1, replyNotifications());
    }

    @Test void deliveryQueueFailureRollsBackReplyStatusAssignmentAndNotification() {
        tx.executeWithoutResult(status -> inquiries.findById(inquiry.getId()).orElseThrow().setSenderEmail("invalid-historical-email"));
        ContactMessage before = inquiries.findById(inquiry.getId()).orElseThrow();
        assertThrows(IllegalStateException.class, () -> service.replyToMessage(inquiry.getId(), "This must roll back", before.getVersion()));
        ContactMessage after = inquiries.findById(inquiry.getId()).orElseThrow();
        assertNull(after.getReplyText()); assertNull(after.getRepliedAt()); assertNull(after.getAssignedTo());
        assertEquals(ContactMessageStatus.OPEN, after.getStatus()); assertEquals(before.getVersion(), after.getVersion());
        assertEquals(0, queuedReplies().size()); assertEquals(0, replyNotifications());
    }

    @Test void administratorReplyFormCarriesTheVersionAndDescribesQueuingRatherThanDelivery() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        SecurityContextHolder.clearContext();
        String html = mvc.perform(get("/admin/inquiries/" + inquiry.getId()).with(user(administrator.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("/reply\"")); assertTrue(html.contains("name=\"version\""));
        mvc.perform(post("/admin/inquiries/" + inquiry.getId() + "/reply").with(user(administrator.getEmail()).roles("ADMIN")).with(csrf())
                .param("replyText", "Reply sent through the administrator form").param("version", inquiry.getVersion().toString()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("successMessage", "Reply saved. The customer's email is queued for delivery."));
        assertEquals(1, queuedReplies().size()); assertEquals(1, replyNotifications());
    }

    @Test void simultaneousIdenticalRepliesProduceOneEmailAndOneNotification() throws Exception {
        var executor = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            Callable<Long> action = () -> { login(); ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); try { return service.replyToMessage(inquiry.getId(), "Concurrent identical advice", inquiry.getVersion()).getId(); } finally { SecurityContextHolder.clearContext(); } };
            var first = executor.submit(action); var second = executor.submit(action);
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            assertEquals(inquiry.getId(), first.get(30, TimeUnit.SECONDS)); assertEquals(inquiry.getId(), second.get(30, TimeUnit.SECONDS));
            assertEquals(1, queuedReplies().size()); assertEquals(1, replyNotifications());
        } finally { executor.shutdownNow(); }
    }

    private void login() { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(administrator.getEmail(), "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))); }
    private List<OutboundEmail> queuedReplies() { return emails.findAll().stream().filter(email -> email.getEventKey().startsWith("inquiry-reply:" + inquiry.getId() + ":")).sorted(Comparator.comparing(OutboundEmail::getId)).toList(); }
    private long replyNotifications() { return notifications.findAll().stream().filter(note -> "INQUIRY".equals(note.getRelatedEntityType()) && inquiry.getId().equals(note.getRelatedEntityId()) && customer.getId().equals(note.getUser().getId())).count(); }
}
