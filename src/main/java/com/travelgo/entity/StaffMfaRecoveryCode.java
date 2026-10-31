package com.travelgo.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "staff_mfa_recovery_codes", uniqueConstraints = @UniqueConstraint(name = "uk_staff_mfa_recovery_hash", columnNames = {"user_id", "code_hash"}))
public class StaffMfaRecoveryCode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "code_hash", nullable = false, length = 64) private String codeHash;
    @Column(name = "consumed_at") private Instant consumedAt;
    public StaffMfaRecoveryCode() { }
    public StaffMfaRecoveryCode(Long userId, String codeHash) { this.userId = userId; this.codeHash = codeHash; }
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getCodeHash() { return codeHash; }
    public Instant getConsumedAt() { return consumedAt; }
    public void consume(Instant now) { consumedAt = now; }
}
