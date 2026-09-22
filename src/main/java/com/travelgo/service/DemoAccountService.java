package com.travelgo.service;

import com.travelgo.config.DemoMode;
import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import com.travelgo.security.RoleNames;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DemoAccountService {
    public enum Account {
        ADMIN("admin", "ADMIN", "Admin", "Administration workspace", "/admin/dashboard"),
        CATALOGUE("catalogue", "TRAVEL_CONSULTANT", "Travel Consultant", "Packages, destinations & hotels", "/staff/packages"),
        VISA("visa", "VISA_OFFICER", "Visa Officer", "Applications & document review", "/staff/visas"),
        CUSTOMER("customer", "CUSTOMER", "Customer", "Bookings & your next journey", "/customer/dashboard");

        private final String key;
        private final String role;
        private final String label;
        private final String description;
        private final String destination;

        Account(String key, String role, String label, String description, String destination) {
            this.key = key; this.role = role; this.label = label;
            this.description = description; this.destination = destination;
        }

        public String getKey() { return key; }
        public String getRole() { return role; }
        public String getLabel() { return label; }
        public String getDescription() { return description; }
        public String getDestination() { return destination; }
        public String getEmail() { return "demo." + key + "@travelgo.example.invalid"; }
    }

    public record Choice(String key, String label, String description) { }
    public record Login(UserDetails principal, String destination) { }

    private final DemoMode mode;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final CustomUserDetailsService details;

    public DemoAccountService(DemoMode mode, UserRepository users, RoleRepository roles,
                              PasswordEncoder encoder, CustomUserDetailsService details) {
        this.mode = mode; this.users = users; this.roles = roles;
        this.encoder = encoder; this.details = details;
    }

    @Transactional(readOnly = true)
    public List<Choice> choices() {
        if (!mode.isEnabled()) return List.of();
        return Arrays.stream(Account.values()).map(account -> {
            String label = account.getLabel();
            if (account == Account.CATALOGUE && users.findByEmail(account.getEmail())
                    .map(user -> user.getRole() != null && "PACKAGE_MANAGER".equals(user.getRole().getRoleName())).orElse(false)) {
                label = "Package Manager";
            }
            return new Choice(account.getKey(), label, account.getDescription());
        }).toList();
    }

    /** Startup only; existing accounts are validated, never reset or adopted. */
    @Transactional
    public void provision() {
        requireDemoMode();
        for (Account account : Account.values()) {
            var existing = users.findByEmailIgnoreCase(account.getEmail());
            if (existing.isPresent()) {
                verify(account, existing.get());
                continue;
            }
            Role role = roles.findByRoleName(account.getRole()).orElseGet(() -> {
                if (account == Account.CATALOGUE) {
                    var legacy = roles.findByRoleName("PACKAGE_MANAGER");
                    if (legacy.isPresent()) return legacy.get();
                }
                return roles.save(new Role(account.getRole(), "TravelGO " + account.getLabel() + " role"));
            });
            // No usable password is published; the allowlisted demo endpoint is the shortcut.
            User user = new User("DEMO · TravelGO " + account.getLabel(), account.getEmail(),
                    encoder.encode(UUID.randomUUID().toString()), role);
            user.setDemoAccount(true);
            users.save(user);
        }
    }

    @Transactional(readOnly = true)
    public Login authenticate(String key) {
        requireDemoMode();
        Account account = Arrays.stream(Account.values()).filter(a -> a.getKey().equals(key)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        User user = users.findByEmail(account.getEmail()).orElseThrow(DemoAccountService::unavailable);
        verify(account, user);
        return new Login(details.loadUserByUsername(user.getEmail()), account.getDestination());
    }

    private void requireDemoMode() {
        if (!mode.isEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static void verify(Account account, User user) {
        if (!user.isDemoAccount() || !user.isActive() || user.getRole() == null
                || !account.getRole().equals(RoleNames.canonical(user.getRole().getRoleName()))
                || !account.getEmail().equals(user.getEmail())) {
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "This demo account is unavailable or has changed. Ask the administrator to review the dedicated demo account.");
    }
}
