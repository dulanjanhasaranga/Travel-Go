package com.travelgo;

import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.service.CustomOAuth2UserService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.*;
import org.springframework.security.oauth2.core.user.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GoogleIdentitySafetyTests {
    final UserRepository users = mock(UserRepository.class);
    final RoleRepository roles = mock(RoleRepository.class);
    final com.travelgo.repository.GoogleAccountIdentityRepository identities = mock(com.travelgo.repository.GoogleAccountIdentityRepository.class);
    final org.springframework.transaction.PlatformTransactionManager manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
    final Map<String, Object> profile = new HashMap<>(Map.of("sub", "test-subject", "email", "Customer@travelgo.test", "name", "Customer", "email_verified", true, "hd", "travelgo.test"));
    final StubGoogle service = new StubGoogle();
    class StubGoogle extends CustomOAuth2UserService {
        StubGoogle() { super(users, roles, identities, java.time.Clock.systemUTC(), manager); }
        @Override protected OAuth2User fetchUser(OAuth2UserRequest request) { return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("provider")), profile, "email"); }
        @Override protected OidcUser fetchOidcUser(OidcUserRequest request) {
            return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("provider")), request.getIdToken(), new org.springframework.security.oauth2.core.oidc.OidcUserInfo(profile), "email");
        }
    }
    @org.junit.jupiter.api.BeforeEach void transactions() {
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
    }
    ClientRegistration registration() { return ClientRegistration.withRegistrationId("google").clientId("testing.apps.googleusercontent.com")
            .clientSecret("testingOnly").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8080/login/oauth2/code/google").scope("openid", "email", "profile")
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth").tokenUri("https://oauth2.googleapis.com/token")
            .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo").userNameAttributeName("email").build(); }
    OAuth2AccessToken token() { return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "testingOnly", Instant.now(), Instant.now().plusSeconds(60)); }
    OAuth2UserRequest request() { return new OAuth2UserRequest(registration(), token()); }
    Role role(String name) { Role role = new Role(); role.setRoleName(name); return role; }
    User customer() { User user = new User(); user.setEmail("Customer@travelgo.test"); user.setPassword("local-password-version"); user.setRole(role("CUSTOMER")); user.setActive(true); return user; }
    @Test void unverifiedProviderEmailCannotCreateOrLinkAnyAccount() {
        profile.put("email_verified", false); assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(request())); verifyNoInteractions(users, roles);
    }
    @Test void staffAdministratorInactiveAndDemoAccountsCannotUseGoogleLogin() {
        for (String name : List.of("ADMIN", "VISA_OFFICER", "TRAVEL_CONSULTANT", "CUSTOMER")) {
            User stored = customer(); stored.setRole(role(name)); if ("CUSTOMER".equals(name)) stored.setActive(false);
            when(users.findByEmailIgnoreCase("customer@travelgo.test")).thenReturn(Optional.of(stored));
            assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(request()));
        }
        User demo = customer(); demo.setDemoAccount(true); when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(demo));
        assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(request())); verify(users, never()).saveAndFlush(any());
    }
    @Test void existingCustomerRetainsStoredEmailAndReceivesLocalCustomerAuthority() {
        User stored = customer(); when(users.findByEmailIgnoreCase("customer@travelgo.test")).thenReturn(Optional.of(stored));
        OAuth2User result = service.loadUser(request()); assertEquals(stored.getEmail(), result.getName());
        var local = assertInstanceOf(org.springframework.security.core.userdetails.UserDetails.class, result);
        assertEquals(stored.getEmail(), local.getUsername()); assertEquals(stored.getPassword(), local.getPassword());
        assertFalse(result.getAttributes().containsKey("password"));
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")), new ArrayList<>(result.getAuthorities())); verify(users, never()).saveAndFlush(any());
    }
    @Test void newVerifiedCustomerReceivesEncodedUnknownPasswordAndCustomerRole() {
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty()); when(roles.findByRoleName("CUSTOMER")).thenReturn(Optional.of(role("CUSTOMER")));
        when(users.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        OAuth2User result = service.loadUser(request()); assertEquals("customer@travelgo.test", result.getName());
        var capture = org.mockito.ArgumentCaptor.forClass(User.class); verify(users).saveAndFlush(capture.capture());
        assertTrue(capture.getValue().getPassword().startsWith("$2a$10$")); assertEquals(60, capture.getValue().getPassword().length());
        assertTrue(capture.getValue().isActive()); assertFalse(capture.getValue().isDemoAccount()); assertEquals("CUSTOMER", capture.getValue().getRole().getRoleName());
    }
    @Test void oversizedNameInvalidEmailAndConcurrentRegistrationCannotBeAdopted() {
        profile.put("name", "x".repeat(101)); when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(request()));
        profile.put("name", "Customer"); profile.put("email", "bad\naddress@travelgo.test");
        assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(request()));
        profile.put("email", "customer@travelgo.test"); when(roles.findByRoleName("CUSTOMER")).thenReturn(Optional.of(role("CUSTOMER")));
        when(users.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("private diagnostic"));
        var failure = assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(request())); assertFalse(failure.getMessage().contains("private diagnostic"));
    }
    @Test void oidcPathValidatesIssuerAndAudienceAndUsesStoredCustomerName() {
        User stored = customer(); when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(stored));
        Instant now = Instant.now(); Map<String, Object> claims = new HashMap<>(profile);
        claims.put("sub", "test-subject"); claims.put("iss", "https://accounts.google.com"); claims.put("aud", List.of(registration().getClientId()));
        var valid = new OidcUserRequest(registration(), token(), new OidcIdToken("signed-token-placeholder", now, now.plusSeconds(60), claims));
        assertEquals(stored.getEmail(), service.loadOidcUser(valid).getName());
        claims.put("aud", List.of("another-client")); var wrong = new OidcUserRequest(registration(), token(), new OidcIdToken("unused", now, now.plusSeconds(60), claims));
        assertThrows(OAuth2AuthenticationException.class, () -> service.loadOidcUser(wrong));
        claims.put("aud", List.of(registration().getClientId())); claims.put("iss", "https://evil.test");
        var issuer = new OidcUserRequest(registration(), token(), new OidcIdToken("unused", now, now.plusSeconds(60), claims));
        assertThrows(OAuth2AuthenticationException.class, () -> service.loadOidcUser(issuer));
    }
    @Test void stableSubjectRetainsAccountWhenProviderEmailChanges() {
        User stored = customer(); com.travelgo.entity.GoogleAccountIdentity identity = new com.travelgo.entity.GoogleAccountIdentity(); identity.setUser(stored);
        when(identities.findBySubject("test-subject")).thenReturn(Optional.of(identity)); profile.put("email", "different@gmail.com");
        assertEquals(stored.getEmail(), service.loadUser(request()).getName()); verifyNoInteractions(users, roles);
    }
    @Test void boundOidcPrincipalKeepsSignedProviderClaimsAndLocalControllerIdentity() {
        User stored = customer(); var identity = new com.travelgo.entity.GoogleAccountIdentity(); identity.setUser(stored);
        when(identities.findBySubject("test-subject")).thenReturn(Optional.of(identity));
        profile.put("email", "changed-provider@gmail.com");
        Instant now = Instant.now(); Map<String, Object> claims = new HashMap<>(profile);
        claims.put("iss", "https://accounts.google.com"); claims.put("aud", List.of(registration().getClientId()));
        var signed = new OidcIdToken("synthetic-id-token", now, now.plusSeconds(60), claims);
        OidcUser principal = service.loadOidcUser(new OidcUserRequest(registration(), token(), signed));
        var local = assertInstanceOf(org.springframework.security.core.userdetails.UserDetails.class, principal);
        assertSame(signed, principal.getIdToken());
        assertEquals("changed-provider@gmail.com", principal.getIdToken().getEmail());
        assertEquals("changed-provider@gmail.com", principal.getUserInfo().getEmail());
        assertEquals(stored.getEmail(), principal.getName()); assertEquals(stored.getEmail(), local.getUsername());
        assertEquals(stored.getPassword(), local.getPassword());
        assertEquals(List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")), new ArrayList<>(principal.getAuthorities()));
        assertFalse(principal.getClaims().containsKey("password")); verifyNoInteractions(users, roles);
    }
    @Test void unboundThirdPartyEmailAndAlreadyBoundDifferentSubjectCannotLinkLocalAccount() {
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(customer())); profile.remove("hd");
        assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(request()));
        profile.put("hd", "travelgo.test"); when(identities.existsByUser_Id(any())).thenReturn(true);
        assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(request())); verify(identities, never()).saveAndFlush(any());
    }
}
