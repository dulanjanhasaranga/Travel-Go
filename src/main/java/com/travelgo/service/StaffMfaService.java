package com.travelgo.service;

import com.travelgo.config.StaffMfaProperties;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.security.*;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffMfaService {
    private static final Set<String> STAFF = Set.of("ADMIN", "TRAVEL_CONSULTANT", "VISA_OFFICER");
    private final StaffMfaProperties properties;
    private final StaffMfaCrypto crypto;
    private final UserRepository users;
    private final StaffMfaCredentialRepository credentials;
    private final StaffMfaRecoveryCodeRepository recovery;
    private final AuditLogRepository audits;
    private final PasswordEncoder passwords;
    private final EntityManager em;
    private final Clock clock;
    public StaffMfaService(StaffMfaProperties properties, StaffMfaCrypto crypto, UserRepository users,
            StaffMfaCredentialRepository credentials, StaffMfaRecoveryCodeRepository recovery,
            AuditLogRepository audits, PasswordEncoder passwords, EntityManager em, Clock clock) {
        this.properties = properties; this.crypto = crypto; this.users = users; this.credentials = credentials;
        this.recovery = recovery; this.audits = audits; this.passwords = passwords; this.em = em; this.clock = clock;
    }
    public record Status(boolean required, boolean enrolled, boolean verified, boolean fresh) { }
    public record Setup(String secret, String account, Instant expiresAt) { }
    public record Result(boolean success, boolean blocked, StaffMfaSession.Proof proof, List<String> recoveryCodes) {
        static Result denied(boolean blocked) { return new Result(false, blocked, null, List.of()); }
    }
    public boolean isEnabled() { return properties.isEnabled(); }
    public boolean isStaff(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getAuthorities().stream().anyMatch(a -> STAFF.stream().anyMatch(r -> a.getAuthority().equals("ROLE_" + r)));
    }
    @Transactional(readOnly = true)
    public Status status(Authentication auth, HttpSession session) {
        if (!properties.isEnabled() || !isStaff(auth)) return new Status(false, false, false, false);
        User user = current(auth, session, false);
        StaffMfaCredential credential = credentials.findById(user.getId()).orElse(null);
        boolean enrolled = credential != null && credential.isEnrolled();
        boolean required = properties.isStaffMandatory() || enrolled || (credential != null && credential.getEnrolledAt() != null);
        StaffMfaSession.Proof proof = StaffMfaSession.proof(session);
        Instant now = clock.instant();
        boolean verified = enrolled && proof != null && proof.userId().equals(user.getId())
                && proof.version().equals(credential.getCredentialVersion())
                && proof.passwordVersion().equals(user.getPassword())
                && proof.permissionHash().equals(CustomUserDetailsService.permissionHash(user.getRole().getPermissions()))
                && proof.role().equals(RoleNames.canonical(user.getRole().getRoleName()))
                && StaffMfaSession.within(proof.verifiedAt(), now, properties.getSessionSeconds());
        return new Status(required, enrolled, verified,
                verified && StaffMfaSession.within(proof.stepUpAt(), now, properties.getStepUpSeconds()));
    }
    /** Reading a pending setup never creates, extends or rotates it. */
    @Transactional(readOnly = true)
    public Optional<Setup> setup(Authentication auth, HttpSession session) {
        requireEnabled(); User user = current(auth, session, false);
        return credentials.findById(user.getId()).filter(c -> !c.isEnrolled() && c.getEncryptedSecret() != null
                && StaffMfaSession.ownsEnrollment(session, user.getId(), c.getCredentialVersion())
                && c.getPendingExpiresAt() != null && c.getPendingExpiresAt().isAfter(clock.instant()))
                .map(c -> new Setup(crypto.reveal(user.getId(), c.getEncryptedSecret()), user.getEmail(), c.getPendingExpiresAt()));
    }
    /** The existing account row serializes absent-row creation and all later token/recovery use. */
    @Transactional
    public Result beginEnrollment(Authentication auth, HttpSession session, String password) {
        requireEnabled(); User user = current(auth, session, true);
        StaffMfaCredential credential = credential(user);
        if (credential.isEnrolled()) return Result.denied(false);
        if (blocked(credential)) return Result.denied(true);
        if (!passwordMatches(password, user)) return failure(user, credential);
        Instant now = clock.instant();
        boolean ownsSetup = StaffMfaSession.ownsEnrollment(session, user.getId(), credential.getCredentialVersion());
        boolean hasLiveSetup = credential.getEncryptedSecret() != null && credential.getPendingExpiresAt() != null
                && credential.getPendingExpiresAt().isAfter(now);
        // A password-only session cannot adopt another browser's setup. After authenticator replacement,
        // even expiry does not let another session take over the factor-authorized enrollment.
        if ((hasLiveSetup || credential.getEnrolledAt() != null) && !ownsSetup) return Result.denied(false);
        // Repeated start posts retain a live secret and its expiry; failed verification budget is never reset here.
        if (!hasLiveSetup) {
            credential.setEncryptedSecret(crypto.protect(user.getId(), crypto.newSecret()));
            credential.setPendingExpiresAt(now.plusSeconds(properties.getChallengeSeconds()));
            credential.setLastAcceptedCounter(-1); credential.renewVersion(); credential.setUpdatedAt(now);
            credentials.save(credential);
            session.setAttribute(StaffMfaSession.ENROLLMENT, new StaffMfaSession.Enrollment(user.getId(), credential.getCredentialVersion()));
            audit(user, "STAFF_MFA_SETUP_STARTED");
        }
        return new Result(true, false, null, List.of());
    }
    @Transactional
    public Result confirmEnrollment(Authentication auth, HttpSession session, String password, String code) {
        requireEnabled(); User user = current(auth, session, true); StaffMfaCredential credential = credential(user);
        if (blocked(credential)) return Result.denied(true);
        if (credential.isEnrolled() || credential.getEncryptedSecret() == null || credential.getPendingExpiresAt() == null
                || !StaffMfaSession.ownsEnrollment(session, user.getId(), credential.getCredentialVersion())
                || !credential.getPendingExpiresAt().isAfter(clock.instant())) return Result.denied(false);
        if (!passwordMatches(password, user)) return failure(user, credential);
        long accepted = crypto.acceptedCounter(crypto.reveal(user.getId(), credential.getEncryptedSecret()), code,
                clock.instant(), credential.getLastAcceptedCounter());
        if (accepted < 0) return failure(user, credential);
        Instant now = clock.instant(); credential.setLastAcceptedCounter(accepted); credential.setEnrolled(true);
        credential.setEnrolledAt(now); credential.setPendingExpiresAt(null); credential.renewVersion(); succeeded(credential);
        List<String> codes = replaceRecoveryCodes(user.getId());
        audit(user, "STAFF_MFA_ENROLLED");
        return new Result(true, false, proof(user, credential, true, session), codes);
    }
    @Transactional
    public Result verify(Authentication auth, HttpSession session, String code, boolean recoveryCode) {
        return authenticate(auth, session, null, code, recoveryCode, false, false);
    }
    @Transactional
    public Result reauthenticate(Authentication auth, HttpSession session, String password, String code, boolean recoveryCode) {
        return authenticate(auth, session, password, code, recoveryCode, true, false);
    }
    @Transactional
    public Result regenerateRecoveryCodes(Authentication auth, HttpSession session, String password, String code, boolean recoveryCode) {
        return authenticate(auth, session, password, code, recoveryCode, true, true);
    }
    @Transactional
    public Result replaceAuthenticator(Authentication auth, HttpSession session, String password, String code, boolean recoveryCode) {
        Result verified = authenticate(auth, session, password, code, recoveryCode, true, false);
        if (!verified.success()) return verified;
        // authenticate holds the account lock for this transaction, so no old token can race this replacement.
        StaffMfaCredential credential = lockedCredential(verified.proof().userId()).orElseThrow();
        credential.setEnrolled(false); credential.renewVersion();
        credential.setLastAcceptedCounter(-1); credential.setEncryptedSecret(crypto.protect(credential.getUserId(), crypto.newSecret()));
        credential.setPendingExpiresAt(clock.instant().plusSeconds(properties.getChallengeSeconds())); credential.setUpdatedAt(clock.instant());
        recovery.deleteByUserId(credential.getUserId()); credentials.save(credential);
        session.setAttribute(StaffMfaSession.ENROLLMENT, new StaffMfaSession.Enrollment(credential.getUserId(), credential.getCredentialVersion()));
        audits.save(new AuditLog("STAFF_MFA_AUTHENTICATOR_REPLACEMENT_STARTED", "StaffMfa", credential.getUserId(), auth.getName(), "Old authenticator and recovery codes revoked."));
        return new Result(true, false, null, List.of());
    }
    private Result authenticate(Authentication auth, HttpSession session, String password, String code,
            boolean recoveryCode, boolean passwordRequired, boolean regenerate) {
        requireEnabled(); User user = current(auth, session, true); StaffMfaCredential credential = credential(user);
        if (!credential.isEnrolled()) return Result.denied(false);
        if (blocked(credential)) return Result.denied(true);
        if (passwordRequired && !passwordMatches(password, user)) return failure(user, credential);
        if (recoveryCode) {
            String hash = crypto.recoveryHash(user.getId(), code);
            var available = recovery.lockAvailableByUserId(user.getId());
            // Flush intended earlier changes, then discard only these managed instances. Hibernate may
            // otherwise reuse gate-loaded state; refresh of an already-locked entity downgrades its SELECT
            // to READ in Hibernate 7, which can restore a stale InnoDB repeatable-read snapshot.
            em.flush(); available.forEach(em::detach);
            available = recovery.lockAvailableByUserId(user.getId());
            available.forEach(candidate -> em.unwrap(org.hibernate.Session.class).setReadOnly(candidate, false));
            var matching = available.stream().filter(c -> c.getConsumedAt() == null && constantEquals(hash, c.getCodeHash())).findFirst();
            if (hash.isEmpty() || matching.isEmpty()) return failure(user, credential);
            matching.get().consume(clock.instant()); recovery.save(matching.get());
        } else {
            long accepted = crypto.acceptedCounter(crypto.reveal(user.getId(), credential.getEncryptedSecret()), code,
                    clock.instant(), credential.getLastAcceptedCounter());
            if (accepted < 0) return failure(user, credential);
            credential.setLastAcceptedCounter(accepted);
        }
        succeeded(credential);
        List<String> codes = List.of();
        if (regenerate) { credential.renewVersion(); codes = replaceRecoveryCodes(user.getId()); }
        audit(user, regenerate ? "STAFF_MFA_RECOVERY_ROTATED" : recoveryCode ? "STAFF_MFA_RECOVERY_USED" : "STAFF_MFA_VERIFIED");
        return new Result(true, false, proof(user, credential, passwordRequired, session), codes);
    }
    private StaffMfaSession.Proof proof(User user, StaffMfaCredential credential, boolean passwordChecked, HttpSession session) {
        Instant now = clock.instant(); Object passwordAt = session.getAttribute(StaffMfaSession.PASSWORD_AT);
        boolean freshPassword = passwordChecked || (passwordAt instanceof Instant at && StaffMfaSession.within(at, now, properties.getStepUpSeconds()));
        return new StaffMfaSession.Proof(user.getId(), credential.getCredentialVersion(), user.getPassword(),
                CustomUserDetailsService.permissionHash(user.getRole().getPermissions()), RoleNames.canonical(user.getRole().getRoleName()),
                now, freshPassword ? now : null);
    }
    private StaffMfaCredential credential(User user) {
        return lockedCredential(user.getId()).orElseGet(() -> credentials.save(new StaffMfaCredential(user.getId(), clock.instant())));
    }
    private Optional<StaffMfaCredential> lockedCredential(Long userId) {
        // MySQL REPEATABLE READ needs a current locking load after the account wait. A locking query can
        // still reuse an OSIV-managed instance; Hibernate refresh of an already-locked entity issues a
        // plain READ. Flush, evict only that instance, and perform a fresh locking load instead.
        var credential = credentials.lockByUserId(userId);
        credential.ifPresent(value -> {
            em.flush(); em.detach(value);
        });
        if (credential.isPresent()) credential = credentials.lockByUserId(userId);
        credential.ifPresent(value -> em.unwrap(org.hibernate.Session.class).setReadOnly(value, false));
        return credential;
    }
    private List<String> replaceRecoveryCodes(Long userId) {
        recovery.deleteByUserId(userId); recovery.flush();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) { String code = crypto.newRecoveryCode(); codes.add(code); recovery.save(new StaffMfaRecoveryCode(userId, crypto.recoveryHash(userId, code))); }
        return List.copyOf(codes);
    }
    private Result failure(User user, StaffMfaCredential credential) {
        Instant now = clock.instant();
        credential.setFailedAttempts(credential.getFailedAttempts() + 1);
        if (credential.getFailedAttempts() >= properties.getMaxAttempts()) {
            credential.setBlockedUntil(now.plusSeconds(properties.getCooldownSeconds())); credential.setFailedAttempts(0);
        }
        credential.setUpdatedAt(now); credentials.save(credential);
        audit(user, "STAFF_MFA_ATTEMPT_REJECTED");
        // Return normally: the rejection/cooldown must commit instead of being rolled back by an exception.
        return Result.denied(blocked(credential));
    }
    private boolean blocked(StaffMfaCredential credential) { return credential.getBlockedUntil() != null && credential.getBlockedUntil().isAfter(clock.instant()); }
    private void succeeded(StaffMfaCredential credential) {
        credential.setFailedAttempts(0); credential.setBlockedUntil(null); credential.setUpdatedAt(clock.instant()); credentials.save(credential);
    }
    private void requireEnabled() { if (!properties.isEnabled()) throw new AccessDeniedException("Staff verification is not available."); }
    private boolean passwordMatches(String password, User user) {
        return password != null && password.length() <= 200 && passwords.matches(password, user.getPassword());
    }
    private static boolean constantEquals(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII), second.getBytes(StandardCharsets.US_ASCII));
    }
    private void audit(User user, String action) { audits.save(new AuditLog(action, "StaffMfa", user.getId(), user.getEmail(), "Staff authentication state changed.")); }
    private User current(Authentication auth, HttpSession session, boolean lock) {
        if (!isStaff(auth) || session == null) throw new AccessDeniedException("Sign in again to verify staff access.");
        User user = users.findByEmailIgnoreCase(auth.getName()).orElseThrow(() -> new AccessDeniedException("Sign in again to verify staff access."));
        if (lock) {
            user = users.lockAccount(user.getId()).orElseThrow(() -> new AccessDeniedException("Staff access is unavailable."));
            Long userId = user.getId();
            em.flush(); em.detach(user);
            user = users.lockAccount(userId).orElseThrow(() -> new AccessDeniedException("Staff access is unavailable."));
        }
        String role = user.getRole() == null ? null : RoleNames.canonical(user.getRole().getRoleName());
        String permissions = role == null ? null : CustomUserDetailsService.permissionHash(user.getRole().getPermissions());
        Set<String> expected = new HashSet<>(); expected.add("ROLE_" + role);
        if (user.getRole() != null) user.getRole().getPermissions().forEach(p -> expected.add("PERM_" + p.getPermissionName()));
        Set<String> actual = new HashSet<>(); auth.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_") || a.startsWith("PERM_")).forEach(actual::add);
        if (!user.isActive() || role == null || !STAFF.contains(role) || !expected.equals(actual)
                || !user.getPassword().equals(session.getAttribute("accountPasswordVersion"))
                || !permissions.equals(session.getAttribute("permissionHash")))
            throw new AccessDeniedException("Your account changed. Sign in again.");
        return user;
    }
}
