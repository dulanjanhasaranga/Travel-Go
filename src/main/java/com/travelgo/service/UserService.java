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

/**
 * Service for user management operations.
 * Handles registration, CRUD, and account status management for all user types.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder, AuditService auditService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder; this.auditService = auditService;
    }

    // ---- Registration ----

    @Transactional
    public User registerCustomer(String name, String email, String password, String phone, String address) {
        if (email == null || email.length()>150 || !email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Enter a valid email address.");
        email=email.trim().toLowerCase(java.util.Locale.ROOT);
        if(name==null||name.trim().length()<2||name.length()>100||password==null||password.length()<6||password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("Check your name and use a password of 6–72 bytes.");
        if(phone!=null&&phone.length()>20||address!=null&&address.length()>1000)throw new IllegalArgumentException("Contact details are too long.");
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("Email already registered: " + email);
        }

        Role customerRole = roleRepository.findByRoleName("CUSTOMER")
                .orElseThrow(() -> new RuntimeException("CUSTOMER role not found"));

        User user = new User(name, email, passwordEncoder.encode(password), customerRole);
        user.setPhone(phone);
        user.setAddress(address);
        return userRepository.save(user);
    }

    // ---- Staff Creation (by Admin) ----

    @Transactional
    public User createStaffAccount(String name, String email, String password,
                                   String phone, String address, String roleName) {
        if (email == null || email.length()>150 || !email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Enter a valid email address.");
        email=email.trim().toLowerCase(java.util.Locale.ROOT);
        if(name==null||name.trim().length()<2||name.length()>100||password==null||password.length()<6||password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("Check your name and use a password of 6–72 bytes.");
        if(phone!=null&&phone.length()>20||address!=null&&address.length()>1000)throw new IllegalArgumentException("Contact details are too long.");
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("Email already registered: " + email);
        }

        Role role = roleRepository.findByRoleName(roleName)
                .orElseThrow(() -> new RuntimeException("Role not found: " + roleName));

        User user = new User(name, email, passwordEncoder.encode(password), role);
        user.setPhone(phone);
        user.setAddress(address);
        return userRepository.save(user);
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
        if(request.getName()==null || request.getName().isBlank() || request.getName().length()>100 || (request.getPhone()!=null && request.getPhone().length()>20) || (request.getAddress()!=null && request.getAddress().length()>1000)) throw new IllegalArgumentException("Check your profile details.");
        owner.setName(request.getName().trim());owner.setPhone(request.getPhone());owner.setAddress(request.getAddress());
        return userRepository.save(owner);
    }

    @Transactional
    public User updateUser(Long id, String name, String phone, String address) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));

        user.setName(name);
        user.setPhone(phone);
        user.setAddress(address);
        return userRepository.save(user);
    }

    @Transactional
    public User updateStaff(Long id, String name, String phone, String address, String roleName) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));

        user.setName(name);
        user.setPhone(phone);
        user.setAddress(address);

        if (roleName != null && !roleName.isEmpty()) {
            Role role = roleRepository.findByRoleName(roleName)
                    .orElseThrow(() -> new RuntimeException("Role not found: " + roleName));
            user.setRole(role);
        }

        return userRepository.save(user);
    }

    // ---- Status Management ----

    @Transactional
    public void toggleUserStatus(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        user.setActive(!user.isActive());
        userRepository.save(user);
    }

    // ---- Role Assignment ----

    @Transactional
    public void assignRole(Long userId, Long roleId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RuntimeException("Role not found: " + roleId));
        user.setRole(role);
        userRepository.save(user);
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
}


