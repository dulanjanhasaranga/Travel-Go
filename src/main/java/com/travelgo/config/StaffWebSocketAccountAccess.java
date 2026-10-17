package com.travelgo.config;

import com.travelgo.repository.UserRepository;
import com.travelgo.security.RoleNames;
import com.travelgo.service.CustomUserDetailsService;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A fresh database check, including permissions, for a previously authenticated staff session. */
@Component
public class StaffWebSocketAccountAccess {
    private static final Set<String> STAFF_ROLES = Set.of("ADMIN", "TRAVEL_CONSULTANT", "VISA_OFFICER");
    private final UserRepository users;
    private final DemoMode demoMode;

    public StaffWebSocketAccountAccess(UserRepository users, DemoMode demoMode) {
        this.users = users;
        this.demoMode = demoMode;
    }

    @Transactional(readOnly = true)
    public boolean isCurrent(Authentication authentication, String passwordBaseline, String permissionBaseline,
            boolean localConnection) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || passwordBaseline == null || passwordBaseline.isBlank() || permissionBaseline == null) return false;
        var user = users.findByEmailIgnoreCase(authentication.getName()).orElse(null);
        if (user == null || !user.isActive() || user.getRole() == null
                || (user.isDemoAccount() && (!demoMode.isEnabled() || !localConnection))
                || !passwordBaseline.equals(user.getPassword())) return false;
        String role = RoleNames.canonical(user.getRole().getRoleName());
        if (role == null || !STAFF_ROLES.contains(role) || user.getRole().getPermissions() == null
                || !permissionBaseline.equals(CustomUserDetailsService.permissionHash(user.getRole().getPermissions()))) return false;
        // Comparing names as well as IDs catches renamed permissions and stale captured authorities.
        Set<String> currentAuthorities = new HashSet<>();
        currentAuthorities.add("ROLE_" + role);
        for (var permission : user.getRole().getPermissions()) {
            if (permission.getPermissionName() == null || permission.getPermissionName().isBlank()) return false;
            currentAuthorities.add("PERM_" + permission.getPermissionName());
        }
        Set<String> capturedAuthorities = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                // Spring Security also supplies authentication-factor authorities; these are not role grants.
                .filter(authority -> authority != null && (authority.startsWith("ROLE_") || authority.startsWith("PERM_")))
                .collect(Collectors.toSet());
        return currentAuthorities.equals(capturedAuthorities);
    }
}
