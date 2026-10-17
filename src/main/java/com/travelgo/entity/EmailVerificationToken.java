package com.travelgo.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="email_verification_tokens")
public class EmailVerificationToken {

    @Id
    @Column(length=64)
    private String tokenHash;

    @ManyToOne(optional=false)
    @JoinColumn(name="user_id")
    private User user;

    @Column(nullable=false)
    private Instant expiresAt;

    @Column(nullable=false)
    private boolean used;

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isUsed() {
        return used;
    }

    public void setUsed(boolean used) {
        this.used = used;
    }
}
