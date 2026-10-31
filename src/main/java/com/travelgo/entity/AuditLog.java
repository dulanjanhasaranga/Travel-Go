package com.travelgo.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_logs")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String entityName;

    private Long entityId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(length = 2000)
    private String details;

    public AuditLog() {}

    public AuditLog(String action, String entityName, Long entityId, String username, String details) {
        this.action = action;
        this.entityName = entityName;
        this.entityId = entityId;
        this.username = username;
        this.timestamp = Instant.now();
        this.details = details;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getEntityName() { return entityName; }
    public void setEntityName(String entityName) { this.entityName = entityName; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
}
