package com.travelgo.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Separate additive table: no existing account password, role or permission columns change. */
@Entity
@Table(name = "staff_mfa_credentials")
public class StaffMfaCredential {
    @Id @Column(name = "user_id") private Long userId;
    @Column(name = "encrypted_secret", length = 256) private String encryptedSecret;
    @Column(name = "enrolled", nullable = false) private boolean enrolled;
    @Column(name = "credential_version", nullable = false, length = 36) private String credentialVersion;
    @Column(name = "last_accepted_counter", nullable = false) private long lastAcceptedCounter = -1;
    @Column(name = "failed_attempts", nullable = false) private int failedAttempts;
    @Column(name = "blocked_until") private Instant blockedUntil;
    @Column(name = "pending_expires_at") private Instant pendingExpiresAt;
    @Column(name = "enrolled_at") private Instant enrolledAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    public StaffMfaCredential() { }
    public StaffMfaCredential(Long userId, Instant now) {
        this.userId = userId; this.credentialVersion = java.util.UUID.randomUUID().toString(); this.updatedAt = now;
    }
    public Long getUserId() { return userId; }
    public String getEncryptedSecret() { return encryptedSecret; }
    public void setEncryptedSecret(String value) { encryptedSecret = value; }
    public boolean isEnrolled() { return enrolled; }
    public void setEnrolled(boolean value) { enrolled = value; }
    public String getCredentialVersion() { return credentialVersion; }
    public void renewVersion() { credentialVersion = java.util.UUID.randomUUID().toString(); }
    public long getLastAcceptedCounter() { return lastAcceptedCounter; }
    public void setLastAcceptedCounter(long value) { lastAcceptedCounter = value; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int value) { failedAttempts = value; }
    public Instant getBlockedUntil() { return blockedUntil; }
    public void setBlockedUntil(Instant value) { blockedUntil = value; }
    public Instant getPendingExpiresAt() { return pendingExpiresAt; }
    public void setPendingExpiresAt(Instant value) { pendingExpiresAt = value; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public void setEnrolledAt(Instant value) { enrolledAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
