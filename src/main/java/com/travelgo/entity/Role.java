package com.travelgo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Role entity - defines user roles in the system.
 * Each user is assigned exactly one role.
 * Roles are linked to permissions via the role_permissions join table.
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_name", nullable = false, unique = true, length = 50)
    private String roleName;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "role_permissions",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private java.util.Set<Permission> permissions = new java.util.HashSet<>();

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // ---- Constructors ----

    public Role() {}

    public Role(String roleName, String description) {
        this.roleName = roleName;
        this.description = description;
    }

    // ---- Getters and Setters ----

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public java.util.Set<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(java.util.Set<Permission> permissions) {
        this.permissions = permissions;
    }
}
