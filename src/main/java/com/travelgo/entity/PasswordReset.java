package com.travelgo.entity;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="password_reset_tokens")
public class PasswordReset {
 @Id @Column(length=64) private String tokenHash;
 @ManyToOne(optional=false) @JoinColumn(name="user_id") private User user;
 @Column(nullable=false) private Instant expiresAt;
 @Column(nullable=false) private boolean used;
 @Column(nullable=false) private String passwordAtIssue;
 public String getTokenHash(){return tokenHash;} public void setTokenHash(String v){tokenHash=v;}
 public User getUser(){return user;} public void setUser(User v){user=v;}
 public Instant getExpiresAt(){return expiresAt;} public void setExpiresAt(Instant v){expiresAt=v;}
 public boolean isUsed(){return used;} public void setUsed(boolean v){used=v;}
 public String getPasswordAtIssue(){return passwordAtIssue;} public void setPasswordAtIssue(String v){passwordAtIssue=v;}
}
