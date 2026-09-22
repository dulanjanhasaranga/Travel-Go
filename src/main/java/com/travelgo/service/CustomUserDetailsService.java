package com.travelgo.service;

import com.travelgo.entity.Permission;
import com.travelgo.entity.User;
import com.travelgo.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Custom UserDetailsService for Spring Security.
 * Loads user from MySQL by email and maps role + permissions to GrantedAuthorities.
 * Role permissions from the role_permissions table are loaded as PERM_* authorities
 * so that admin permission checkbox changes actually control access.
 * Also checks if the account is active before allowing login.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final com.travelgo.config.DemoMode demoMode;

    public CustomUserDetailsService(UserRepository userRepository, com.travelgo.config.DemoMode demoMode) {
        this.userRepository = userRepository;
        this.demoMode = demoMode;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        // Map role to Spring Security authority (e.g., ROLE_ADMIN, ROLE_CUSTOMER)
        String authority = "ROLE_" + com.travelgo.security.RoleNames.canonical(user.getRole().getRoleName());

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                user.isActive() && (!user.isDemoAccount() || demoMode.isEnabled()),
                true,                  // accountNonExpired
                true,                  // credentialsNonExpired
                true,                  // accountNonLocked
                buildAuthorities(authority, user.getRole().getPermissions())
        );
    }

    /**
     * Build the full authority list: the ROLE_* authority plus every assigned
     * permission as a PERM_* authority so that @PreAuthorize / hasAuthority
     * checks honour the admin permission checkboxes.
     */
    private List<SimpleGrantedAuthority> buildAuthorities(String roleAuthority, Set<Permission> permissions) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(roleAuthority));
        if (permissions != null) {
            for (Permission p : permissions) {
                authorities.add(new SimpleGrantedAuthority("PERM_" + p.getPermissionName()));
            }
        }
        return authorities;
    }

    /**
     * Compute a deterministic hash of a role's current permission set.
     * Used by AccountSessionFilter to detect permission changes mid-session.
     */
    public static String permissionHash(Set<Permission> permissions) {
        if (permissions == null || permissions.isEmpty()) return "empty";
        return permissions.stream()
                .map(p -> p.getId().toString())
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("empty");
    }
}
