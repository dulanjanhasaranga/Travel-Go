package com.travelgo.service;

import com.travelgo.entity.SystemSettings;
import com.travelgo.repository.SystemSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for system settings management.
 * Operates on a singleton row (id = 1) in the system_settings table.
 */
@Service
public class SystemSettingsService {

    private final SystemSettingsRepository settingsRepository;

    public SystemSettingsService(SystemSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    /**
     * Get the system settings. Creates default if not found.
     */
    public SystemSettings getSettings() {
        return settingsRepository.findById(1L)
                .orElseGet(() -> {
                    SystemSettings defaults = new SystemSettings();
                    defaults.setCompanyName("TravelGO");
                    defaults.setCompanyDescription("Your trusted travel partner for unforgettable journeys.");
                    defaults.setCompanyEmail("info@travelgo.com");
                    defaults.setCompanyPhone("+1 (555) 123-4567");
                    defaults.setCompanyAddress("123 Travel Street, Adventure City, TC 10001");
                    defaults.setWebsiteUrl("http://localhost:8080");
                    defaults.setWebsiteStatus("ACTIVE");
                    defaults.setMaintenanceMessage("We are currently performing scheduled maintenance. Please check back soon.");
                    defaults.setCurrency("USD");
                    defaults.setTimezone("UTC");
                    defaults.setBusinessHours("{\"monday\":\"9:00 AM - 6:00 PM\",\"tuesday\":\"9:00 AM - 6:00 PM\",\"wednesday\":\"9:00 AM - 6:00 PM\",\"thursday\":\"9:00 AM - 6:00 PM\",\"friday\":\"9:00 AM - 6:00 PM\",\"saturday\":\"10:00 AM - 4:00 PM\",\"sunday\":\"Closed\"}");
                    return settingsRepository.save(defaults);
                });
    }

    /**
     * Update system profile (name, description, logo).
     */
    @Transactional
    public SystemSettings updateProfile(String companyName, String companyDescription, String logoUrl) {
        SystemSettings settings = getSettings();
        settings.setCompanyName(companyName);
        settings.setCompanyDescription(companyDescription);
        settings.setLogoUrl(logoUrl);
        return settingsRepository.save(settings);
    }

    /**
     * Update company information.
     */
    @Transactional
    public SystemSettings updateCompanyInfo(String companyName, String companyDescription, String websiteUrl) {
        SystemSettings settings = getSettings();
        settings.setCompanyName(companyName);
        settings.setCompanyDescription(companyDescription);
        settings.setWebsiteUrl(websiteUrl);
        return settingsRepository.save(settings);
    }

    /**
     * Update contact information.
     */
    @Transactional
    public SystemSettings updateContactInfo(String companyEmail, String companyPhone, String companyAddress) {
        SystemSettings settings = getSettings();
        settings.setCompanyEmail(companyEmail);
        settings.setCompanyPhone(companyPhone);
        settings.setCompanyAddress(companyAddress);
        return settingsRepository.save(settings);
    }

    /**
     * Update business hours (stored as JSON string).
     */
    @Transactional
    public SystemSettings updateBusinessHours(String businessHoursJson) {
        SystemSettings settings = getSettings();
        settings.setBusinessHours(businessHoursJson);
        return settingsRepository.save(settings);
    }

    /**
     * Update general system settings.
     */
    @Transactional
    public SystemSettings updateGeneralSettings(String currency, String timezone) {
        SystemSettings settings = getSettings();
        settings.setCurrency(currency);
        settings.setTimezone(timezone);
        return settingsRepository.save(settings);
    }

    /**
     * Update website status (ACTIVE / MAINTENANCE).
     */
    @Transactional
    public SystemSettings updateWebsiteStatus(String websiteStatus, String maintenanceMessage) {
        SystemSettings settings = getSettings();
        settings.setWebsiteStatus(websiteStatus);
        settings.setMaintenanceMessage(maintenanceMessage);
        return settingsRepository.save(settings);
    }

    /**
     * Check if the website is in maintenance mode.
     */
    public boolean isMaintenanceMode() {
        return "MAINTENANCE".equals(getSettings().getWebsiteStatus());
    }
}
