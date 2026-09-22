package com.travelgo;

import com.travelgo.entity.Notification;
import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.NotificationRepository;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.security.RoleNames;
import com.travelgo.service.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Independent, committed H2 fixture. No application database or seeded accounts are used. */
@SpringBootTest(properties = {"spring.config.import=",
    "spring.datasource.url=jdbc:h2:mem:travelgo-notification-inbox;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.open-in-view=true",
    "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false"})
@ActiveProfiles("test")
@Import(NotificationInboxTests.Time.class)
class NotificationInboxTests {
    static class MutableClock extends Clock {
        Instant instant = Instant.parse("2030-06-01T10:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        public Instant instant() { return instant; }
    }
    @TestConfiguration static class Time { @Bean @Primary MutableClock notificationClock() { return new MutableClock(); } }
    @Autowired NotificationService inbox;
    @Autowired NotificationRepository notifications;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JdbcTemplate sql;
    @Autowired MutableClock clock;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    User owner;
    User other;

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        clock.instant = Instant.parse("2030-06-01T10:00:00Z");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = account("CUSTOMER");
        other = account("CUSTOMER");
    }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }
    User account(String roleName) {
        Role role = roles.findByRoleName(roleName).orElseGet(() -> roles.save(new Role(roleName, "Isolated notification test")));
        return users.save(new User("TEST Notification user", UUID.randomUUID() + "@example.invalid", "unused-test-password", role));
    }
    void login(User account) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(account.getEmail(), "unused",
            List.of(new SimpleGrantedAuthority("ROLE_" + RoleNames.canonical(account.getRole().getRoleName())))));
    }
    MockHttpServletRequestBuilder as(User account, MockHttpServletRequestBuilder request) {
        SecurityContextHolder.clearContext();
        return request.with(user(account.getEmail()).roles(RoleNames.canonical(account.getRole().getRoleName())));
    }
    Notification publish(User account, String type, String entityType, Long id) {
        return inbox.publish(account, type, "TEST " + type, "A saved travel update.", entityType, id);
    }

    @Test void feedReturnsOwnLatestFiveAndRealTotalCountWithoutChangingReadState() throws Exception {
        Notification newest = null;
        for (int i = 0; i < 7; i++) newest = publish(owner, "BOOKING_UPDATED", "BOOKING", 100L + i);
        publish(other, "VISA_APPROVED", "VISA", 77L);
        for (int refresh = 0; refresh < 2; refresh++) {
            mvc.perform(as(owner, get("/notifications/feed"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.unreadCount").value(7)).andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].id").value(newest.getId()))
                .andExpect(jsonPath("$.items[*].type", everyItem(is("BOOKING_UPDATED"))))
                .andExpect(jsonPath("$.items[*].read", everyItem(is(false))));
        }
        assertEquals(7L, sql.queryForObject("select count(*) from notifications where user_id=? and is_read=false", Long.class, owner.getId()));
    }

    @Test void readAndTimestampPersistAcrossRequestsAndDoNotChangeOnRepeatedSubmission() throws Exception {
        Notification note = publish(owner, "BOOKING_CREATED", "BOOKING", 123L);
        mvc.perform(as(owner, post("/notifications/" + note.getId() + "/read")).with(csrf()).accept("application/json"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0))
            .andExpect(jsonPath("$.url").value("/customer/bookings/123"));
        LocalDateTime firstRead = sql.queryForObject("select read_at from notifications where id=?", LocalDateTime.class, note.getId());
        assertEquals(LocalDateTime.of(2030, 6, 1, 10, 0), firstRead);
        clock.instant = clock.instant.plusSeconds(600);
        mvc.perform(as(owner, post("/notifications/" + note.getId() + "/read")).with(csrf()).accept("application/json"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
        assertEquals(firstRead, sql.queryForObject("select read_at from notifications where id=?", LocalDateTime.class, note.getId()));
        mvc.perform(as(owner, get("/notifications/feed"))).andExpect(jsonPath("$.items[0].read").value(true));
        mvc.perform(as(owner, get("/notifications"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("TEST BOOKING_CREATED")))
            .andExpect(content().string(containsString(">Read</span>")));
    }

    @Test void foreignAndMissingNotificationsReturnSame404AndCannotBeRead() throws Exception {
        Notification foreign = publish(other, "VISA_APPROVED", "VISA", 5L);
        mvc.perform(as(owner, post("/notifications/" + foreign.getId() + "/read")).with(csrf()).accept("application/json"))
            .andExpect(status().isNotFound());
        mvc.perform(as(owner, post("/notifications/9223372036854775807/read")).with(csrf()).accept("application/json"))
            .andExpect(status().isNotFound());
        assertFalse(notifications.findById(foreign.getId()).orElseThrow().isRead());
        login(owner);
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> inbox.markRead(foreign.getId()));
        assertEquals(404, error.getStatusCode().value());
    }

    @Test void readAllOnlyUpdatesCurrentAccountAndPreservesOriginalReadTime() throws Exception {
        Notification existingRead = publish(owner, "BOOKING_CREATED", "BOOKING", 2L);
        Notification unread = publish(owner, "VISA_APPROVED", "VISA", 2L);
        Notification foreign = publish(other, "PAYMENT_SUCCESS", "PAYMENT", 2L);
        login(owner); inbox.markRead(existingRead.getId());
        LocalDateTime firstRead = notifications.findById(existingRead.getId()).orElseThrow().getReadAt();
        clock.instant = clock.instant.plusSeconds(60);
        mvc.perform(as(owner, post("/notifications/read-all")).with(csrf()).accept("application/json"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
        assertEquals(firstRead, notifications.findById(existingRead.getId()).orElseThrow().getReadAt());
        assertEquals(firstRead.plusMinutes(1), notifications.findById(unread.getId()).orElseThrow().getReadAt());
        assertFalse(notifications.findById(foreign.getId()).orElseThrow().isRead());
        mvc.perform(as(owner, post("/notifications/read-all")).with(csrf()))
            .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/notifications"));
        mvc.perform(as(owner, get("/notifications/feed"))).andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test void mutationsRequireCsrfAndAuthenticatedSessionAndCannotUseGet() throws Exception {
        Notification note = publish(owner, "VISA_APPROVED", "VISA", 1L);
        for (String path : List.of("/notifications/" + note.getId() + "/read", "/notifications/read-all")) {
            mvc.perform(as(owner, post(path))).andExpect(status().isForbidden());
            mvc.perform(as(owner, post(path)).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
            mvc.perform(as(owner, get(path))).andExpect(status().isMethodNotAllowed());
            SecurityContextHolder.clearContext();
            mvc.perform(post(path).with(csrf())).andExpect(status().is3xxRedirection());
        }
        SecurityContextHolder.clearContext();
        mvc.perform(get("/notifications/feed")).andExpect(status().is3xxRedirection());
        assertFalse(notifications.findById(note.getId()).orElseThrow().isRead());
    }

    @Test void categoriesUnreadAndPaginationAreFilteredInDatabase() throws Exception {
        for (int i = 0; i < 15; i++) publish(owner, "VISA_APPROVED", "VISA", (long) i + 1);
        Notification paid = publish(owner, "PAYMENT_SUCCESS", "PAYMENT", 1L);
        publish(other, "VISA_REJECTED", "VISA", 20L);
        login(owner);
        assertEquals(12, inbox.inbox("visa", false, 0).getNumberOfElements());
        assertEquals(15, inbox.inbox("visa", false, 0).getTotalElements());
        assertEquals(3, inbox.inbox("visa", false, 1).getNumberOfElements());
        assertEquals(1, inbox.inbox("payment", true, 0).getTotalElements());
        inbox.markRead(paid.getId());
        assertEquals(0, inbox.inbox("payment", true, 0).getTotalElements());
        assertEquals(1, inbox.inbox("payment", false, 0).getTotalElements());
        mvc.perform(as(owner, get("/notifications").param("category", "visa"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("Page 1 of 2")))
            .andExpect(content().string(not(containsString("notification-card-meta\">PAYMENT SUCCESS"))));
        mvc.perform(as(owner, get("/notifications").param("category", "untrusted"))).andExpect(status().isBadRequest());
        mvc.perform(as(owner, get("/notifications").param("page", "-1"))).andExpect(status().isBadRequest());
    }

    @Test void oldUncategorizedRecordsStillAppearAndHaveSafeFallbackLinks() throws Exception {
        Notification legacy = new Notification(); legacy.setUser(owner); legacy.setTitle("Old update"); legacy.setMessage("A retained historical notification.");
        notifications.save(legacy);
        mvc.perform(as(owner, get("/notifications/feed"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].type").value("GENERAL"))
            .andExpect(jsonPath("$.items[0].url").value("/notifications"));
        mvc.perform(as(owner, get("/customer/notifications"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("Old update")));
    }

    @Test void untrustedStoredTextIsEscapedAndSubmittedRedirectsCannotLeaveLocalRoutes() throws Exception {
        Notification note = inbox.publish(owner, "BOOKING_UPDATED", "<script>alert('test')</script>",
            "<img src=x onerror=alert('test')>", "BOOKING", 123L);
        mvc.perform(as(owner, get("/notifications"))).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("<script>alert('test')</script>"))))
            .andExpect(content().string(containsString("&lt;script&gt;")))
            .andExpect(content().string(not(containsString("<img src=x onerror="))));
        mvc.perform(as(owner, post("/notifications/" + note.getId() + "/read")).with(csrf()).param("destination", "https://example.invalid"))
            .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/customer/bookings/123"));
        note.setRelatedEntityType("javascript:"); notifications.save(note);
        mvc.perform(as(owner, post("/notifications/" + note.getId() + "/read")).with(csrf()))
            .andExpect(redirectedUrl("/notifications"));
        assertThrows(IllegalArgumentException.class, () -> publish(owner, "VISA_APPROVED", "https://example.invalid", 1L));
    }

    @Test void eachRoleAndLegacyCatalogueAliasReceiveOnlyTheirOwnInboxAndUsefulRoute() throws Exception {
        for (String role : List.of("ADMIN", "TRAVEL_CONSULTANT", "PACKAGE_MANAGER", "VISA_OFFICER", "CUSTOMER")) {
            User account = account(role);
            String type = role.equals("VISA_OFFICER") ? "VISA" : "INQUIRY";
            publish(account, role.equals("VISA_OFFICER") ? "VISA_SUBMITTED" : "SUPPORT_RESPONSE", type, 47L);
            String expected = switch (role) {
                case "ADMIN" -> "/admin/inquiries/47";
                case "TRAVEL_CONSULTANT", "PACKAGE_MANAGER" -> "/staff/inquiries/47";
                case "VISA_OFFICER" -> "/staff/visas";
                default -> "/customer/inquiries";
            };
            mvc.perform(as(account, get("/notifications/feed"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1)).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].url").value(expected));
            mvc.perform(as(account, get("/notifications"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("notification-center")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        }
    }
}
