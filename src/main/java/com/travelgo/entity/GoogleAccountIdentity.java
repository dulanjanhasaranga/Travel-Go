package com.travelgo.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Google's stable subject is the identity key; a later email change never moves account ownership. */
@Entity @Table(name = "google_account_identities")
public class GoogleAccountIdentity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 255) private String subject;
    @OneToOne(fetch = FetchType.EAGER) @JoinColumn(name = "user_id", nullable = false, unique = true) private User user;
    @Column(nullable = false) private Instant linkedAt;
    public Long getId() { return id; }
    public String getSubject() { return subject; } public void setSubject(String value) { subject = value; }
    public User getUser() { return user; } public void setUser(User value) { user = value; }
    public Instant getLinkedAt() { return linkedAt; } public void setLinkedAt(Instant value) { linkedAt = value; }
}
