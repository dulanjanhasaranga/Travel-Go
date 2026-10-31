package com.travelgo.config;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import com.travelgo.util.PasswordPolicy;

/**
 * Seeds non-sensitive reference data on application startup.
 * Login accounts are never created with embedded default passwords. An empty instance can
 * create one administrator only when both bootstrap credentials are explicitly supplied.
 */
@Component
@org.springframework.context.annotation.Profile("!test & !mysql-test")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "travelgo.data-initializer.enabled", havingValue = "true", matchIfMissing = true)
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final SystemSettingsRepository settingsRepository;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapAdminEmail;
    private final String bootstrapAdminPassword;
    private final String bootstrapAdminName;

    public DataInitializer(RoleRepository roleRepository,
                           PermissionRepository permissionRepository,
                           UserRepository userRepository,
                           SystemSettingsRepository settingsRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${travelgo.bootstrap.admin.email:}") String bootstrapAdminEmail,
                           @Value("${travelgo.bootstrap.admin.password:}") String bootstrapAdminPassword,
                           @Value("${travelgo.bootstrap.admin.name:}") String bootstrapAdminName) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapAdminEmail = bootstrapAdminEmail;
        this.bootstrapAdminPassword = bootstrapAdminPassword;
        this.bootstrapAdminName = bootstrapAdminName;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("=== TravelGO Data Initializer ===");
        
        // 1. Create permissions
        createPermissions();

        // 2. Create roles
        boolean newAdminRole = roleRepository.findByRoleName("ADMIN").isEmpty();
        boolean newConsultantRole = roleRepository.findByRoleName("TRAVEL_CONSULTANT").isEmpty();
        boolean newOfficerRole = roleRepository.findByRoleName("VISA_OFFICER").isEmpty();
        Role adminRole = createRole("ADMIN", "System administrator with full access");
        createRole("CUSTOMER", "Registered customer");
        createRole("TRAVEL_CONSULTANT", "Travel consultant staff member");
        Role consultantRole = roleRepository.findByRoleName("TRAVEL_CONSULTANT")
                .or(() -> roleRepository.findByRoleName("PACKAGE_MANAGER"))
                .orElse(null);
        Role officerRole = createRole("VISA_OFFICER", "Visa processing officer");

        // 3. Assign all admin permissions to ADMIN role
        if (newAdminRole) assignAdminPermissions(adminRole);

        // 3b. Assign default permissions to staff roles so PERM_* URL rules grant access
        assignStaffPermissions(newConsultantRole ? consultantRole : null, newOfficerRole ? officerRole : null);

        // 4. Create only an explicitly configured first administrator.
        createBootstrapAdministrator(adminRole);

        // 5. Create default system settings
        createDefaultSettings();

        System.out.println("=== Data initialization complete ===");
    }

    private void createPermissions() {
        // Admin module permissions
        createPermission("USER_VIEW", "View users list", "USER");
        createPermission("USER_MANAGE", "Create, update, and deactivate users", "USER");
        createPermission("STAFF_VIEW", "View staff list", "STAFF");
        createPermission("STAFF_MANAGE", "Create, update, and deactivate staff", "STAFF");
        createPermission("ROLE_VIEW", "View roles", "ROLE");
        createPermission("ROLE_MANAGE", "Manage role-permission assignments", "ROLE");
        createPermission("PERMISSION_VIEW", "View permissions", "PERMISSION");
        createPermission("SYSTEM_SETTINGS_VIEW", "View system settings", "SYSTEM");
        createPermission("SYSTEM_SETTINGS_MANAGE", "Update system settings", "SYSTEM");
        createPermission("DASHBOARD_VIEW", "Access admin dashboard", "DASHBOARD");

        // Staff module permissions
        createPermission("PACKAGE_VIEW", "View tour packages", "PACKAGE");
        createPermission("PACKAGE_MANAGE", "Manage tour packages", "PACKAGE");
        createPermission("DESTINATION_VIEW", "View destinations", "DESTINATION");
        createPermission("DESTINATION_MANAGE", "Manage destinations", "DESTINATION");
        createPermission("VISA_VIEW", "View visa applications", "VISA");
        createPermission("VISA_MANAGE", "Process visa applications", "VISA");
        createPermission("BOOKING_VIEW", "View bookings", "BOOKING");
        createPermission("BOOKING_MANAGE", "Manage bookings", "BOOKING");
        createPermission("PAYMENT_VIEW", "View payment records", "PAYMENT");
        createPermission("PAYMENT_MANAGE", "Process and manage payments", "PAYMENT");
    }

    private void createPermission(String name, String description, String module) {
        if (!permissionRepository.existsByPermissionName(name)) {
            permissionRepository.save(new Permission(name, description, module));
            System.out.println("  Created permission: " + name);
        }
    }

    private Role createRole(String roleName, String description) {
        return roleRepository.findByRoleName(roleName)
                .orElseGet(() -> {
                    Role role = roleRepository.save(new Role(roleName, description));
                    System.out.println("  Created role: " + roleName);
                    return role;
                });
    }

    private void assignAdminPermissions(Role adminRole) {
        Set<Permission> allPermissions = new HashSet<>(permissionRepository.findAll());
        if (adminRole.getPermissions().size() != allPermissions.size()) {
            adminRole.setPermissions(allPermissions);
            roleRepository.save(adminRole);
            System.out.println("  Assigned all permissions to ADMIN role");
        }
    }

    /**
     * Assign role-appropriate default permissions to staff roles.
     * Travel Consultants get catalogue and booking permissions.
     * Visa Officers get visa and payment permissions.
     * This runs idempotently — only adds permissions that are missing.
     */
    private void assignStaffPermissions(Role consultantRole, Role officerRole) {
        if (consultantRole != null) {
            assignPermissionsIfMissing(consultantRole,
                    "DESTINATION_VIEW", "DESTINATION_MANAGE",
                    "PACKAGE_VIEW", "PACKAGE_MANAGE",
                    "BOOKING_VIEW", "BOOKING_MANAGE");
        }
        if (officerRole != null) {
            assignPermissionsIfMissing(officerRole,
                    "VISA_VIEW", "VISA_MANAGE",
                    "PAYMENT_VIEW", "PAYMENT_MANAGE");
        }
    }

    private void assignPermissionsIfMissing(Role role, String... permissionNames) {
        Set<String> existing = role.getPermissions().stream()
                .map(Permission::getPermissionName)
                .collect(java.util.stream.Collectors.toSet());
        boolean changed = false;
        for (String name : permissionNames) {
            if (!existing.contains(name)) {
                permissionRepository.findByPermissionName(name).ifPresent(p -> role.getPermissions().add(p));
                changed = true;
            }
        }
        if (changed) {
            roleRepository.save(role);
            System.out.println("  Assigned default permissions to " + role.getRoleName());
        }
    }

    private void createBootstrapAdministrator(Role adminRole) {
        if (!userRepository.findByRoleRoleName("ADMIN").isEmpty()) return;

        String email = normalized(bootstrapAdminEmail);
        String password = bootstrapAdminPassword == null ? "" : bootstrapAdminPassword;
        if (email.isEmpty() && password.isEmpty()) return;
        if (email.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException("Set both TRAVELGO_BOOTSTRAP_ADMIN_EMAIL and TRAVELGO_BOOTSTRAP_ADMIN_PASSWORD to create the first administrator.");
        }
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || email.length() > 150) {
            throw new IllegalStateException("TRAVELGO_BOOTSTRAP_ADMIN_EMAIL must be a valid email address.");
        }
        if (!PasswordPolicy.isValid(password)) {
            throw new IllegalStateException("TRAVELGO_BOOTSTRAP_ADMIN_PASSWORD must be 12 to 72 UTF-8 bytes.");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("The configured bootstrap administrator email already belongs to a non-admin account.");
        }

        User user = new User();
        user.setName(bootstrapName(bootstrapAdminName));
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(adminRole);
        user.setActive(true);
        userRepository.save(user);
        System.out.println("  Created configured bootstrap administrator.");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String bootstrapName(String value) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) return "Initial Administrator";
        if (result.length() > 100) {
            throw new IllegalStateException("TRAVELGO_BOOTSTRAP_ADMIN_NAME must be 100 characters or fewer.");
        }
        return result;
    }

    private void createDefaultSettings() {
        if (settingsRepository.count() == 0) {
            SystemSettings settings = new SystemSettings();
            settings.setCompanyName("TravelGO");
            settings.setCompanyDescription("Tour packages, accommodation and visa assistance from our travel team in Colombo.");
            settings.setCompanyEmail("info@travelgo.com");
            settings.setCompanyPhone("0763233204");
            settings.setCompanyAddress("175 Main Street, 11, Colombo, Sri Lanka");
            settings.setWebsiteUrl("http://localhost:8081");
            settings.setWebsiteStatus("ACTIVE");
            settings.setMaintenanceMessage("We are currently performing scheduled maintenance. Please check back soon.");
            settings.setCurrency("USD");
            settings.setTimezone("UTC");
            settings.setBusinessHours("Contact us for current opening hours.");
            settingsRepository.save(settings);
            System.out.println("  Created default system settings");
        }
    }

}
