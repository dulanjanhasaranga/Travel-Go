package com.travelgo.entity;

import jakarta.persistence.*;

/**
 * Permission entity - defines granular permissions that can be assigned to roles.
 * The 'module' field groups permissions by feature area (USER, STAFF, SYSTEM, etc.)
 * so that future team members can easily add permissions for their modules.
 */
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permission_name", nullable = false, unique = true, length = 100)
    private String permissionName;

    @Column(length = 255)
    private String description;

    @Column(length = 50)
    private String module;

    // ---- Constructors ----

    public Permission() {}

    public Permission(String permissionName, String description, String module) {
        this.permissionName = permissionName;
        this.description = description;
        this.module = module;
    }

    // ---- Getters and Setters ----

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPermissionName() {
        return permissionName;
    }

    public void setPermissionName(String permissionName) {
        this.permissionName = permissionName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }
}
