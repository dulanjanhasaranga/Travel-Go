package com.travelgo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * SystemSettings entity - singleton row storing all system/company configuration.
 * Only one row should exist in this table (id = 1).
 * Stores company info, contact details, business hours, and website status.
 */
@Entity
@Table(name = "system_settings")
public class SystemSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "company_description", columnDefinition = "TEXT")
    private String companyDescription;

    @Column(name = "company_email", length = 150)
    private String companyEmail;

    @Column(name = "company_phone", length = 20)
    private String companyPhone;

    @Column(name = "company_address", columnDefinition = "TEXT")
    private String companyAddress;

    @Column(name = "website_url", length = 255)
    private String websiteUrl;

    // Stored as JSON string, e.g.: {"monday":"9:00 AM - 5:00 PM","tuesday":"9:00 AM - 5:00 PM",...}
    @Column(name = "business_hours", columnDefinition = "TEXT")
    private String businessHours;

    // ACTIVE or MAINTENANCE
    @Column(name = "website_status", length = 20, nullable = false)
    private String websiteStatus = "ACTIVE";

    @Column(name = "maintenance_message", columnDefinition = "TEXT")
    private String maintenanceMessage;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(length = 10)
    private String currency = "USD";

    @Column(length = 50)
    private String timezone = "UTC";

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        this.updatedAt = LocalDateTime.now();
    }

    // ---- Constructors ----

    public SystemSettings() {}

    // ---- Getters and Setters ----

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getCompanyDescription() {
        return companyDescription;
    }

    public void setCompanyDescription(String companyDescription) {
        this.companyDescription = companyDescription;
    }

    public String getCompanyEmail() {
        return companyEmail;
    }

    public void setCompanyEmail(String companyEmail) {
        this.companyEmail = companyEmail;
    }

    public String getCompanyPhone() {
        return companyPhone;
    }

    public void setCompanyPhone(String companyPhone) {
        this.companyPhone = companyPhone;
    }

    public String getCompanyAddress() {
        return companyAddress;
    }

    public void setCompanyAddress(String companyAddress) {
        this.companyAddress = companyAddress;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    public String getBusinessHours() {
        return businessHours;
    }
    public String getDisplayBusinessHours() {
        if(businessHours==null||!businessHours.trim().startsWith("{"))return businessHours;
        var matcher=java.util.regex.Pattern.compile("\"([A-Za-z]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(businessHours);
        var lines=new java.util.ArrayList<String>();
        while(matcher.find()){String day=matcher.group(1);lines.add(Character.toUpperCase(day.charAt(0))+day.substring(1)+": "+matcher.group(2));}
        return lines.isEmpty()?businessHours:String.join(" · ",lines);
    }

    public void setBusinessHours(String businessHours) {
        this.businessHours = businessHours;
    }

    public String getWebsiteStatus() {
        return websiteStatus;
    }

    public void setWebsiteStatus(String websiteStatus) {
        this.websiteStatus = websiteStatus;
    }

    public String getMaintenanceMessage() {
        return maintenanceMessage;
    }

    public void setMaintenanceMessage(String maintenanceMessage) {
        this.maintenanceMessage = maintenanceMessage;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String logoUrl) {
        this.logoUrl = logoUrl;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
