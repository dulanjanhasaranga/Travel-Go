package com.travelgo.service;

import com.travelgo.entity.User;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.entity.GoogleAccountIdentity;
import com.travelgo.repository.GoogleAccountIdentityRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/** Verified Google sign-in is customer-only; it cannot bypass local account suspension. */
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final GoogleAccountIdentityRepository identities;
    private final java.time.Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    public CustomOAuth2UserService(UserRepository users, RoleRepository roles, GoogleAccountIdentityRepository identities,
                                   java.time.Clock clock, org.springframework.transaction.PlatformTransactionManager manager) {
        this.users = users; this.roles = roles; this.identities = identities; this.clock = clock;
        transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        requireGoogle(request);
        OAuth2User provider = fetchUser(request);
        User customer = customer(provider.getAttributes());
        Map<String, Object> attributes = new java.util.HashMap<>(provider.getAttributes());
        attributes.put("email", customer.getEmail());
        return new CustomerOAuth2Principal(customer, authorities(customer), attributes);
    }

    public OidcUser loadOidcUser(OidcUserRequest request) {
        requireGoogle(request);
        var token = request.getIdToken();
        String issuer = token.getIssuer() == null ? "" : token.getIssuer().toString();
        if (!("https://accounts.google.com".equals(issuer) || "accounts.google.com".equals(issuer))
                || !token.getAudience().contains(request.getClientRegistration().getClientId()))
            throw rejected("The Google identity could not be verified.");
        OidcUser provider = fetchOidcUser(request);
        User customer = customer(provider.getAttributes());
        // Spring validates the ID token before this service; keep the provider's original signed claims.
        return new CustomerOidcPrincipal(customer, authorities(customer), provider);
    }

    protected OAuth2User fetchUser(OAuth2UserRequest request) { return super.loadUser(request); }
    protected OidcUser fetchOidcUser(OidcUserRequest request) { return new OidcUserService().loadUser(request); }

    private void requireGoogle(OAuth2UserRequest request) {
        if (!"google".equals(request.getClientRegistration().getRegistrationId()))
            throw rejected("This identity provider is not supported.");
    }
    private User customer(Map<String, Object> profile) {
        try { return transaction.execute(status -> customerWithinTransaction(profile)); }
        catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            throw rejected("This account changed during sign-in. Please sign in again.");
        }
    }
    private User customerWithinTransaction(Map<String, Object> profile) {
        if (!Boolean.TRUE.equals(profile.get("email_verified")) && !Boolean.TRUE.equals(profile.get("verified_email")))
            throw rejected("Verify your Google email address before signing in.");
        String subject = profile.get("sub") instanceof String ? (String) profile.get("sub") : "";
        if (subject.isBlank() || subject.length() > 255) throw rejected("The Google account identity is unavailable.");
        var boundIdentity = identities.findBySubject(subject).orElse(null);
        if (boundIdentity != null) { requireActiveCustomer(boundIdentity.getUser()); return boundIdentity.getUser(); }
        Object emailValue = profile.get("email");
        String email = emailValue instanceof String ? ((String) emailValue).trim().toLowerCase(java.util.Locale.ROOT) : "";
        if (email.length() > 150 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw rejected("The Google email address is unavailable.");
        User existing = users.findByEmailIgnoreCase(email).orElse(null);
        if (existing != null) {
            requireActiveCustomer(existing);
            // Google is authoritative for Gmail and a verified Workspace domain, not every third-party address.
            String domain = email.substring(email.indexOf('@') + 1);
            if (!("gmail.com".equals(domain) || "googlemail.com".equals(domain)
                    || (profile.get("hd") instanceof String && domain.equals(profile.get("hd")))))
                throw rejected("Use your existing customer login to verify this third-party email account.");
            if (identities.existsByUser_Id(existing.getId())) throw rejected("This customer account already has a different Google identity.");
            bind(subject, existing);
            return existing;
        }
        String name = profile.get("name") instanceof String ? ((String) profile.get("name")).trim() : "";
        if (name.isBlank() || name.length() > 100) throw rejected("The Google account name is unavailable.");
        User customer = new User();
        customer.setName(name); customer.setEmail(email);
        // Unknown random password prevents local password login until a verified recovery is requested.
        byte[] passwordEntropy = new byte[32]; new java.security.SecureRandom().nextBytes(passwordEntropy);
        customer.setPassword(new BCryptPasswordEncoder(10).encode(java.util.HexFormat.of().formatHex(passwordEntropy)));
        customer.setRole(roles.findByRoleName("CUSTOMER").orElseThrow(() -> rejected("Customer registration is unavailable.")));
        customer.setActive(true); customer.setDemoAccount(false);
        customer = users.saveAndFlush(customer); bind(subject, customer); return customer;
    }
    private void bind(String subject, User customer) {
        GoogleAccountIdentity identity = new GoogleAccountIdentity(); identity.setSubject(subject); identity.setUser(customer);
        identity.setLinkedAt(clock.instant()); identities.saveAndFlush(identity);
    }
    private void requireActiveCustomer(User existing) {
        if (!existing.isActive() || existing.isDemoAccount() || existing.getRole() == null
                || !"CUSTOMER".equals(com.travelgo.security.RoleNames.canonical(existing.getRole().getRoleName())))
            throw rejected("Google sign-in is available only for active customer accounts. Use your agency login or contact support.");
    }
    private List<GrantedAuthority> authorities(User customer) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        if (customer.getRole().getPermissions() != null)
            customer.getRole().getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p.getPermissionName())));
        return authorities;
    }
    private OAuth2AuthenticationException rejected(String message) {
        return new OAuth2AuthenticationException(new OAuth2Error("access_denied"), message);
    }

    /** Customer controllers consume UserDetails for both local and Google sessions. */
    private static final class CustomerOAuth2Principal extends DefaultOAuth2User implements UserDetails {
        private final String username;
        private final String password;
        CustomerOAuth2Principal(User customer, List<GrantedAuthority> authorities, Map<String, Object> attributes) {
            super(authorities, attributes, "email");
            username = customer.getEmail();
            password = customer.getPassword();
        }
        @Override public String getName() { return username; }
        @Override public String getUsername() { return username; }
        @Override public String getPassword() { return password; }
    }

    /** Keep original signed OIDC claims while resolving application ownership by the bound local email. */
    private static final class CustomerOidcPrincipal extends DefaultOidcUser implements UserDetails {
        private final String username;
        private final String password;
        CustomerOidcPrincipal(User customer, List<GrantedAuthority> authorities, OidcUser provider) {
            super(authorities, provider.getIdToken(), provider.getUserInfo(), "email");
            username = customer.getEmail();
            password = customer.getPassword();
        }
        @Override public String getName() { return username; }
        @Override public String getUsername() { return username; }
        @Override public String getPassword() { return password; }
    }
}
