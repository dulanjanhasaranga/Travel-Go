package com.travelgo.service;

import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import com.travelgo.entity.EmailVerificationToken;
import com.travelgo.repository.EmailVerificationTokenRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.time.Clock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import com.travelgo.util.PasswordPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;

import java.util.regex.Pattern;

/**
 * Service for user management operations.
 * Handles registration, CRUD, and account status management for all user types.
 */
@Service
public class UserService {

    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final AdminAccess adminAccess;
    private final EmailVerificationTokenRepository verificationTokens;
    private final ObjectProvider<JavaMailSender> mail;
    private final Clock clock;
    private final String baseUrl;
    private final String from;
    private final boolean mailEnabled;
    private final AccountEmailService accountEmails;
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(UserService.class);

    public UserService(UserRepository userRepository, RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder, AuditService auditService, AdminAccess adminAccess,
                       EmailVerificationTokenRepository verificationTokens,
                       ObjectProvider<JavaMailSender> mail, Clock clock,
                       @Value("${travelgo.public-base-url:http://localhost:8081}") String baseUrl,
                       @Value("${travelgo.mail.from:no-reply@travelgo.example}") String from,
                       @Value("${travelgo.mail.enabled:false}") boolean mailEnabled,
                       AccountEmailService accountEmails) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder; this.auditService = auditService;
        this.adminAccess = adminAccess;
        this.verificationTokens = verificationTokens;
        this.mail = mail;
        this.clock = clock;
        this.baseUrl = baseUrl;
        this.from = from;
        this.mailEnabled = mailEnabled;
        this.accountEmails = accountEmails;
    }

    // ---- Registration ----

    @Transactional
    public User registerCustomer(String name, String email, String password, String phone, String address) {
        if (email == null || email.length()>150 || !email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Enter a valid email address.");
        email=email.trim().toLowerCase(java.util.Locale.ROOT);
        if(name==null||name.trim().length()<2||name.length()>100||!PasswordPolicy.isValid(password))throw new IllegalArgumentException("Enter a name between 2 and 100 characters. Use at least 12 characters for the password and shorten it if it is too long.");
        if(phone!=null&&phone.length()>20||address!=null&&address.length()>1000)throw new IllegalArgumentException("Use up to 20 characters for the phone number and 1,000 for the address.");
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("An account already uses this email address.");
        }

        if (!isEmailVerificationConfigured()) {
            throw new IllegalStateException("Registration is temporarily unavailable. Contact the agency for help.");
        }

        Role customerRole = roleRepository.findByRoleName("CUSTOMER")
                .orElseThrow(() -> new RuntimeException("CUSTOMER role not found"));

        User user = new User(name, email, passwordEncoder.encode(password), customerRole);
        user.setPhone(phone);
        user.setAddress(address);
        user.setActive(false);
        user = userRepository.saveAndFlush(user);

        issueVerification(user);
        return user;
    }

    /** Resends only an unfinished self-registration; administrative suspensions stay suspended. */
    @Transactional
    public void resendVerification(String email) {
        if (!isEmailVerificationConfigured()) {
            throw new IllegalStateException("Email verification is temporarily unavailable. Contact the agency for help.");
        }
        if (email == null || email.length() > 150
                || !email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) return;
        var found = userRepository.findByEmailIgnoreCase(email.trim());
        if (found.isEmpty()) return;
        User user = userRepository.lockAccount(found.get().getId()).orElseThrow();
        if (user.isActive() || user.isDemoAccount() || user.getRole() == null
                || !"CUSTOMER".equals(user.getRole().getRoleName())) return;

        // Account changes revoke unused tokens. Their absence is not permission to reactivate an account.
        var previous = verificationTokens.findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc(user.getId());
        if (previous.isEmpty()) return;
        // Verification lifetime is 24 hours. Persisted issuance time makes this limit independent of browser sessions.
        var nextAllowed = previous.get().getExpiresAt().minusSeconds(86400).plusSeconds(60);
        if (clock.instant().isBefore(nextAllowed)) return;
        verificationTokens.invalidateUnusedForUser(user.getId());
        issueVerification(user);
    }

    private void issueVerification(User user) {

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        EmailVerificationToken verification = new EmailVerificationToken();
        verification.setTokenHash(hash(token));
        verification.setUser(user);
        verification.setExpiresAt(clock.instant().plusSeconds(86400)); // 24 hours
        verification.setUsed(false);
        verificationTokens.saveAndFlush(verification);

        logger.info("Created an email verification request for account {}", user.getId());

        accountEmails.verification(user, token, verification.getTokenHash());

    }

    @Transactional
    public void verifyEmail(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("This verification link is invalid. Use the link from your verification email.");
        String tokenHash = hash(token);
        Long userId = verificationTokens.findUserIdByHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("This verification link is invalid."));
        // Account changes and verification always lock the account before its tokens.
        User user = userRepository.lockAccount(userId).orElseThrow();
        EmailVerificationToken verification = verificationTokens.lockByHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("This verification link is invalid."));
        
        if (verification.isUsed() || !clock.instant().isBefore(verification.getExpiresAt())
                || !userId.equals(verification.getUser().getId()) || user.isActive()
                || user.getRole() == null || !"CUSTOMER".equals(user.getRole().getRoleName())) {
            throw new IllegalArgumentException("This verification link is invalid or has expired. Request a new verification email.");
        }
        
        user.setActive(true);
        userRepository.save(user);
        
        verification.setUsed(true);
        verificationTokens.save(verification);
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Staff Creation (by Admin) ----

    @Transactional
    public User createStaffAccount(String name, String email, String password,
                                   String phone, String address, String roleName) {
        PasswordPolicy.requireValid(password);
        return createStaffAccountWithPasswordHash(name, email, passwordEncoder.encode(password), phone, address, roleName);
    }

    /**
     * Creates an account from a BCrypt value prepared by the approval service.
     * Keeping this path separate prevents an approved request from ever needing
     * to retain a raw password in its persisted payload.
     */
    @Transactional
    public User createStaffAccountWithPasswordHash(String name, String email, String passwordHash,
                                                   String phone, String address, String roleName) {
        lockAdministratorRole();
        User actor = adminAccess.requireAdministrator("STAFF_MANAGE");
        if (email == null || email.length()>150 || !email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Enter a valid email address.");
        email=email.trim().toLowerCase(java.util.Locale.ROOT);
        if(name==null||name.trim().length()<2||name.length()>100)throw new IllegalArgumentException("Enter a staff name between 2 and 100 characters.");
        if (!isBcryptHash(passwordHash)) throw new IllegalArgumentException("A valid BCrypt password hash is required.");
        if(phone!=null&&phone.length()>20||address!=null&&address.length()>1000)throw new IllegalArgumentException("Use up to 20 characters for the phone number and 1,000 for the address.");
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("An account already uses this email address.");
        }

        Role role = requireStaffRole(roleName);

        User user = new User(name.trim(), email, passwordHash, role);
        user.setPhone(phone);
        user.setAddress(address);
        User saved = userRepository.save(user);
        adminAccess.record(actor, "CREATE", "User", saved.getId(), "Created staff account with role " + role.getRoleName() + ".");
        return saved;
    }

    private boolean isBcryptHash(String passwordHash) {
        return passwordHash != null && BCRYPT_HASH.matcher(passwordHash).matches();
    }

    // ---- Find Operations ----

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public Optional<User> getUserById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<User> getUserByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public List<User> searchUsers(String query) {
        return userRepository.findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(query, query);
    }

    public List<User> getCustomers() {
        return userRepository.findByRoleRoleName("CUSTOMER");
    }

    public List<User> getStaffMembers() {
        // Staff = everyone except CUSTOMER
        return userRepository.findByRoleRoleNameNotAndIsActive("CUSTOMER", true);
    }

    public List<User> getAllStaff() {
        // All non-customer users regardless of active status
        List<User> all = userRepository.findAll();
        return all.stream()
                .filter(u -> !"CUSTOMER".equals(u.getRole().getRoleName()))
                .toList();
    }

    // ---- Update Operations ----

    @Transactional
    public User updateOwnProfile(com.travelgo.dto.ProfileRequest request) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(auth == null || !auth.isAuthenticated()) throw new org.springframework.security.access.AccessDeniedException("Please sign in.");
        User owner = userRepository.findByEmail(auth.getName()).orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Account unavailable."));
        if(!owner.isActive() || !"CUSTOMER".equals(owner.getRole().getRoleName())) throw new org.springframework.security.access.AccessDeniedException("Customer access required.");
        if(request.getName()==null || request.getName().isBlank() || request.getName().length()>100 || (request.getPhone()!=null && request.getPhone().length()>20) || (request.getAddress()!=null && request.getAddress().length()>1000)) throw new IllegalArgumentException("Enter your name and keep each profile field within its length limit.");
        owner.setName(request.getName().trim());owner.setPhone(request.getPhone());owner.setAddress(request.getAddress());
        return userRepository.save(owner);
    }

    @Transactional
    public User updateUser(Long id, String name, String phone, String address) {
        lockAdministratorRole();
        User actor = adminAccess.requireAdministrator("USER_MANAGE");
        validateContactDetails(name, phone, address);
        User user = userRepository.lockAccount(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));

        user.setName(name.trim());
        user.setPhone(phone);
        user.setAddress(address);
        User saved = userRepository.save(user);
        adminAccess.record(actor, "UPDATE", "User", id, "Updated account contact details.");
        return saved;
    }

    @Transactional
    public User updateStaff(Long id, String name, String phone, String address, String roleName) {
        lockAdministratorRole();
        User actor = adminAccess.requireAdministrator("STAFF_MANAGE");
        validateContactDetails(name, phone, address);
        Role role = requireStaffRole(roleName);
        User user = userRepository.lockAccount(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));

        if ("CUSTOMER".equals(user.getRole().getRoleName())) {
            throw new IllegalArgumentException("Manage customer accounts in Users.");
        }
        preserveActiveAdministrator(user, user.isActive(), role);

        user.setName(name.trim());
        user.setPhone(phone);
        user.setAddress(address);

        user.setRole(role);

        User saved = userRepository.save(user);
        verificationTokens.invalidateUnusedForUser(id);
        adminAccess.record(actor, "UPDATE", "User", id, "Updated staff details and assigned role " + role.getRoleName() + ".");
        return saved;
    }

    // ---- Status Management ----

    @Transactional
    public void toggleUserStatus(Long id) {
        lockAdministratorRole();
        User actor = adminAccess.requireAdministrator("USER_MANAGE", "STAFF_MANAGE");
        User user = userRepository.lockAccount(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        preserveActiveAdministrator(user, !user.isActive(), user.getRole());
        user.setActive(!user.isActive());
        userRepository.save(user);
        verificationTokens.invalidateUnusedForUser(id);
        adminAccess.record(actor, "UPDATE_STATUS", "User", id, user.isActive() ? "Activated account." : "Deactivated account.");
    }

    // ---- Role Assignment ----

    @Transactional
    public void assignRole(Long userId, Long roleId) {
        lockAdministratorRole();
        User actor = adminAccess.requireAdministrator("USER_MANAGE");
        User user = userRepository.lockAccount(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found: " + roleId));
        if (!java.util.Set.of("ADMIN", "CUSTOMER", "TRAVEL_CONSULTANT", "VISA_OFFICER")
                .contains(com.travelgo.security.RoleNames.canonical(role.getRoleName()))) {
            throw new IllegalArgumentException("This role does not have access to a website workspace. Choose a supported role.");
        }
        preserveActiveAdministrator(user, user.isActive(), role);
        user.setRole(role);
        userRepository.save(user);
        verificationTokens.invalidateUnusedForUser(userId);
        adminAccess.record(actor, "ASSIGN_ROLE", "User", userId, "Assigned role " + role.getRoleName() + ".");
    }

    private void lockAdministratorRole() {
        roleRepository.lockByRoleName("ADMIN")
                .orElseThrow(() -> new IllegalStateException("Administrator role is unavailable."));
    }

    private void preserveActiveAdministrator(User user, boolean active, Role nextRole) {
        if (user.isActive() && "ADMIN".equals(user.getRole().getRoleName())
                && (!active || !"ADMIN".equals(nextRole.getRoleName()))
                && userRepository.countByRoleRoleNameAndIsActive("ADMIN", true) <= 1) {
            throw new IllegalStateException("Keep at least one active administrator. Activate another administrator before this change.");
        }
    }

    static void validateContactDetails(String name, String phone, String address) {
        if (name == null || name.trim().length() < 2 || name.length() > 100) {
            throw new IllegalArgumentException("Enter a name between 2 and 100 characters.");
        }
        if ((phone != null && phone.length() > 20) || (address != null && address.length() > 1000)) {
            throw new IllegalArgumentException("Use at most 20 characters for the phone and 1,000 for the address.");
        }
    }

    Role requireStaffRole(String roleName) {
        if (roleName == null || !java.util.Set.of("ADMIN", "TRAVEL_CONSULTANT", "VISA_OFFICER")
                .contains(com.travelgo.security.RoleNames.canonical(roleName))) {
            throw new IllegalArgumentException("Choose an administrator, travel consultant or visa officer role.");
        }
        return roleRepository.findByRoleName(roleName)
                .orElseThrow(() -> new IllegalArgumentException("The selected role is unavailable."));
    }

    // ---- Statistics ----

    public long getTotalUsers() {
        return userRepository.count();
    }

    public long getActiveUsers() {
        return userRepository.countByIsActive(true);
    }

    public long getInactiveUsers() {
        return userRepository.countByIsActive(false);
    }

    public long getTotalStaff() {
        return userRepository.countByRoleRoleNameNot("CUSTOMER");
    }

    public boolean isEmailVerificationConfigured() {
        return com.travelgo.util.MailConfigurationStatus.isConfigured(mailEnabled, mail.getIfAvailable());
    }
}





