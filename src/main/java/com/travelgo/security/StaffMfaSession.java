package com.travelgo.security;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Instant;

/** Server-side proof only. Submitted codes and setup/recovery secrets are never stored in a session. */
public final class StaffMfaSession {
    public static final String PROOF = StaffMfaSession.class.getName() + ".proof";
    public static final String PASSWORD_AT = StaffMfaSession.class.getName() + ".passwordAt";
    public static final String ENROLLMENT = StaffMfaSession.class.getName() + ".enrollment";
    private StaffMfaSession() { }
    public record Proof(Long userId, String version, String passwordVersion, String permissionHash, String role,
                        Instant verifiedAt, Instant stepUpAt) implements Serializable { }
    /** Unpredictable credential version is an internal enrollment handle, never a browser field or URL. */
    public record Enrollment(Long userId, String version) implements Serializable { }
    public static void clear(HttpSession session) {
        if (session != null) { session.removeAttribute(PROOF); session.removeAttribute(PASSWORD_AT); session.removeAttribute(ENROLLMENT); }
    }
    public static boolean ownsEnrollment(HttpSession session, Long userId, String version) {
        return session != null && session.getAttribute(ENROLLMENT) instanceof Enrollment handle
                && handle.userId().equals(userId) && handle.version().equals(version);
    }
    public static Proof proof(HttpSession session) {
        return session != null && session.getAttribute(PROOF) instanceof Proof value ? value : null;
    }
    public static boolean within(Instant timestamp, Instant now, int seconds) {
        return timestamp != null && !timestamp.isAfter(now) && timestamp.plusSeconds(seconds).isAfter(now);
    }
}
