package com.travelgo;

import com.travelgo.entity.User;
import com.travelgo.repository.GoogleAccountIdentityRepository;
import com.travelgo.service.CustomOAuth2UserService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Real local transactions only; no Google network or account is used. */
class GoogleIdentityPersistenceTests extends WorkflowIntegrationTests {
    @Autowired CustomOAuth2UserService google;
    @Autowired GoogleAccountIdentityRepository identities;
    Map<String, Object> profile(String subject, String email) {
        return new HashMap<>(Map.of("sub", subject, "email", email, "name", "Identity test customer", "email_verified", true,
                "hd", email.substring(email.indexOf('@') + 1)));
    }
    User link(Map<String, Object> profile) { return ReflectionTestUtils.invokeMethod(google, "customer", profile); }
    @Test void persistedSubjectKeepsOriginalLocalOwnerAfterProviderEmailChanges() {
        String subject = "subject-" + UUID.randomUUID();
        User linked = link(profile(subject, customer.getEmail())); assertEquals(customer.getId(), linked.getId());
        assertEquals(customer.getId(), identities.findBySubject(subject).orElseThrow().getUser().getId());
        User changed = link(profile(subject, other.getEmail())); assertEquals(customer.getId(), changed.getId());
        assertEquals(customer.getEmail(), userRepo.findById(customer.getId()).orElseThrow().getEmail());
        assertEquals(other.getEmail(), userRepo.findById(other.getId()).orElseThrow().getEmail());
    }
    @Test void newGoogleCustomerAndIdentityAreStoredTogetherWithoutRewritingExistingAccounts() {
        String subject = "subject-" + UUID.randomUUID(); String email = "new-" + UUID.randomUUID() + "@travelgo.test";
        long before = userRepo.count(); User created = link(profile(subject, email.toUpperCase(Locale.ROOT)));
        assertEquals(email, created.getEmail()); assertEquals(before + 1, userRepo.count());
        assertTrue(created.getPassword().startsWith("$2a$10$")); assertEquals("CUSTOMER", created.getRole().getRoleName());
        assertEquals(created.getId(), identities.findBySubject(subject).orElseThrow().getUser().getId());
    }
    @Test void secondSubjectCannotAdoptAnAlreadyLinkedCustomerAndSuspensionRemainsEffective() {
        String subject = "subject-" + UUID.randomUUID(); link(profile(subject, customer.getEmail()));
        assertThrows(OAuth2AuthenticationException.class, () -> link(profile("another-" + UUID.randomUUID(), customer.getEmail())));
        tx.executeWithoutResult(status -> userRepo.findById(customer.getId()).orElseThrow().setActive(false));
        assertThrows(OAuth2AuthenticationException.class, () -> link(profile(subject, customer.getEmail())));
    }
    @Test void unverifiedOrStaffIdentityCannotCreateAnyBinding() {
        String subject = "subject-" + UUID.randomUUID(); var unverified = profile(subject, customer.getEmail()); unverified.put("email_verified", false);
        assertThrows(OAuth2AuthenticationException.class, () -> link(unverified)); assertTrue(identities.findBySubject(subject).isEmpty());
        assertThrows(OAuth2AuthenticationException.class, () -> link(profile(subject, admin.getEmail()))); assertTrue(identities.findBySubject(subject).isEmpty());
    }
}
