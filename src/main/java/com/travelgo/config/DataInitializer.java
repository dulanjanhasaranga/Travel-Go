package com.travelgo.config;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Seeds non-sensitive reference data on application startup.
 * Login accounts are never created with embedded default passwords. An empty instance can
 * create one administrator only when both bootstrap credentials are explicitly supplied.
 */
@Component
@org.springframework.context.annotation.Profile("!test & !mysql-test")
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final SystemSettingsRepository settingsRepository;
    private final PasswordEncoder passwordEncoder;
    private final TourPackageRepository tourPackageRepository;
    private final com.travelgo.repository.DestinationRepository destinationRepository;
    private final com.travelgo.repository.DepartureRepository departureRepository;
    private final com.travelgo.service.OriginalCatalogueService originalCatalogue;
    private final String bootstrapAdminEmail;
    private final String bootstrapAdminPassword;
    private final String bootstrapAdminName;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public DataInitializer(RoleRepository roleRepository,
                           PermissionRepository permissionRepository,
                           UserRepository userRepository,
                           SystemSettingsRepository settingsRepository,
                           PasswordEncoder passwordEncoder,
                           TourPackageRepository tourPackageRepository,
                           com.travelgo.repository.DestinationRepository destinationRepository,
                           com.travelgo.repository.DepartureRepository departureRepository,
                           com.travelgo.service.OriginalCatalogueService originalCatalogue,
                           org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                           @Value("${travelgo.bootstrap.admin.email:}") String bootstrapAdminEmail,
                           @Value("${travelgo.bootstrap.admin.password:}") String bootstrapAdminPassword,
                           @Value("${travelgo.bootstrap.admin.name:}") String bootstrapAdminName) {
        this.originalCatalogue=originalCatalogue;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.passwordEncoder = passwordEncoder;
        this.tourPackageRepository = tourPackageRepository;
        this.destinationRepository = destinationRepository;
        this.departureRepository = departureRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapAdminEmail = bootstrapAdminEmail;
        this.bootstrapAdminPassword = bootstrapAdminPassword;
        this.bootstrapAdminName = bootstrapAdminName;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("=== TravelGO Data Initializer ===");
        
        try {
            System.out.println("Altering payments table...");
            jdbcTemplate.execute("ALTER TABLE payments MODIFY payment_type VARCHAR(50) NOT NULL");
            System.out.println("Table altered successfully!");
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 1. Create permissions
        createPermissions();

        // 2. Create roles
        Role adminRole = createRole("ADMIN", "System administrator with full access");
        createRole("CUSTOMER", "Registered customer");
        roleRepository.findByRoleName("TRAVEL_CONSULTANT")
                .or(() -> roleRepository.findByRoleName("PACKAGE_MANAGER"))
                .orElseGet(() -> createRole("TRAVEL_CONSULTANT", "Travel consultant staff member"));
        createRole("VISA_OFFICER", "Visa processing officer");

        // 3. Assign all admin permissions to ADMIN role
        assignAdminPermissions(adminRole);

        // 4. Create only an explicitly configured first administrator.
        createBootstrapAdministrator(adminRole);

        // 5. Create default system settings
        createDefaultSettings();

        // Deduplicate existing packages based on name
        // Deduplicate existing packages based on name
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TourPackage pkg : tourPackageRepository.findAll()) {
            String n = pkg.getName().toLowerCase(java.util.Locale.ROOT).trim();
            if (seen.contains(n)) {
                // Delete duplicate and its departures
                departureRepository.findByTourPackage_Id(pkg.getId()).forEach(departureRepository::delete);
                tourPackageRepository.delete(pkg);
                System.out.println("  Deleted duplicate package: " + pkg.getName());
            } else {
                seen.add(n);
                
                // Update images for existing packages - by name
                if (n.contains("europe")) pkg.setImage("/images/europe_pkg_4k.jpg");
                if (n.contains("tokyo")) pkg.setImage("/images/tokyo_pkg_4k.jpg");
                if (n.contains("dubai")) pkg.setImage("/images/dubai_pkg_4k.jpg");
                if (n.contains("rome") || n.contains("ital")) pkg.setImage("/images/rome_pkg_4k.jpg");
                if (n.contains("london")) pkg.setImage("/images/london_pkg_4k.jpg");
                if (n.contains("swiss") || n.contains("alp")) pkg.setImage("/images/swiss_pkg_4k.jpg");
                if (n.contains("santorini")) pkg.setImage("/images/santorini_pkg_4k.jpg");
                if (n.contains("maldives")) pkg.setImage("/images/maldives_pkg_4k.jpg");
                if (n.contains("kenya")) pkg.setImage("/images/kenya_pkg_4k.jpg");
                if (n.contains("bangkok")) pkg.setImage("/images/bangkok_4k.jpg");
                if (n.contains("istanbul")) pkg.setImage("/images/istanbul_4k.jpg");
                tourPackageRepository.save(pkg);
            }
        }

        // Update Destinations
        for (Destination d : destinationRepository.findAll()) {
            String c = d.getCity().toLowerCase(Locale.ROOT);
            if (c.contains("bangkok")) d.setImage("/images/bangkok_4k.jpg");
            else if (c.contains("istanbul")) d.setImage("/images/istanbul_4k.jpg");
            else if (c.contains("dubai")) d.setImage("/images/dubai_desert_4k.jpg");
            else if (c.contains("maldives")) d.setImage("/images/maldives_island_escape_4k.jpg");
            destinationRepository.save(d);
        }

        // 6. Seed default destinations, categories, tour packages, and hotels
        originalCatalogue.install();

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
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
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
            settings.setCompanyDescription("Your trusted travel partner for unforgettable journeys around the world. We offer curated tour packages, visa assistance, and personalized travel planning services.");
            settings.setCompanyEmail("info@travelgo.com");
            settings.setCompanyPhone("+1 (555) 123-4567");
            settings.setCompanyAddress("123 Travel Street, Adventure City, TC 10001");
            settings.setWebsiteUrl("http://localhost:8080");
            settings.setWebsiteStatus("ACTIVE");
            settings.setMaintenanceMessage("We are currently performing scheduled maintenance. Please check back soon.");
            settings.setCurrency("USD");
            settings.setTimezone("UTC");
            settings.setBusinessHours("Mon - Fri: 9:00 AM - 6:00 PM | Sat: 10:00 AM - 4:00 PM");
            settingsRepository.save(settings);
            System.out.println("  Created default system settings");
        } else {
            // Fix existing raw JSON businessHours if needed
            settingsRepository.findAll().forEach(s -> {
                if (s.getBusinessHours() != null && s.getBusinessHours().startsWith("{")) {
                    s.setBusinessHours("Mon - Fri: 9:00 AM - 6:00 PM | Sat: 10:00 AM - 4:00 PM");
                    settingsRepository.save(s);
                }
            });
        }
    }

}
