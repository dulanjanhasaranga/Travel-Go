package com.travelgo.service;

import com.travelgo.entity.SystemSettings;
import com.travelgo.repository.SystemSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for system settings management.
 * Operates on the existing singleton row in the system_settings table.
 */
@Service
public class SystemSettingsService {

    private final SystemSettingsRepository settingsRepository;
    private final AdminAccess adminAccess;
    private final com.travelgo.repository.RoleRepository roles;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public SystemSettingsService(SystemSettingsRepository settingsRepository, AdminAccess adminAccess, com.travelgo.repository.RoleRepository roles) {
        this.settingsRepository = settingsRepository;
        this.adminAccess = adminAccess;
        this.roles = roles;
    }

    /**
     * Get the system settings. Creates default if not found.
     */
    @Transactional
    public SystemSettings getSettings() {
        var existing = settingsRepository.findFirstByOrderByIdAsc();
        if (existing.isPresent()) return existing.get();
        // A stable reference row serializes first-time initialization even when no settings row exists yet.
        roles.lockByRoleName("ADMIN").orElseThrow(() -> new IllegalStateException("Administrator role is unavailable."));
        // A locking read sees the latest committed singleton even under MySQL REPEATABLE READ;
        // a second ordinary read could retain the earlier empty snapshot after waiting for the role lock.
        return settingsRepository.lockSettings().stream().findFirst()
                .orElseGet(() -> {
                    SystemSettings defaults = new SystemSettings();
                    defaults.setCompanyName("TravelGO");
                    defaults.setCompanyDescription("Tour packages, accommodation and visa assistance from our travel team in Colombo.");
                    defaults.setCompanyEmail("info@travelgo.com");
                    defaults.setCompanyPhone("0763233204");
                    defaults.setCompanyAddress("175 Main Street, 11, Colombo, Sri Lanka");
                    defaults.setWebsiteUrl("http://localhost:8081");
                    defaults.setWebsiteStatus("ACTIVE");
                    defaults.setMaintenanceMessage("We are currently performing scheduled maintenance. Please check back soon.");
                    defaults.setCurrency("USD");
                    defaults.setTimezone("UTC");
                    defaults.setBusinessHours("Contact us for current opening hours.");
                    return settingsRepository.save(defaults);
                });
    }

    /**
     * Update system profile (name, description, logo).
     */
    @Transactional
    public SystemSettings updateProfile(String companyName, String companyDescription, String logoUrl) {
        var actor = adminAccess.requireAdministrator("SYSTEM_SETTINGS_MANAGE");
        validateCompany(companyName, companyDescription);
        if (logoUrl != null && !logoUrl.isBlank() && !logoUrl.matches("/images/[A-Za-z0-9_./-]+")) validateWebsiteUrl(logoUrl);
        if (logoUrl != null && (logoUrl.length() > 500 || logoUrl.contains(".."))) throw new IllegalArgumentException("Choose a valid logo image address.");
        SystemSettings settings = lockedSettings();
        settings.setCompanyName(companyName.trim());
        settings.setCompanyDescription(companyDescription);
        settings.setLogoUrl(logoUrl);
        return save(actor, settings, "Updated company identity and logo address.");
    }

    /**
     * Update company information.
     */
    @Transactional
    public SystemSettings updateCompanyInfo(String companyName, String companyDescription, String websiteUrl) {
        var actor = adminAccess.requireAdministrator("SYSTEM_SETTINGS_MANAGE");
        validateCompany(companyName, companyDescription);
        validateWebsiteUrl(websiteUrl);
        SystemSettings settings = lockedSettings();
        settings.setCompanyName(companyName.trim());
        settings.setCompanyDescription(companyDescription);
        settings.setWebsiteUrl(websiteUrl == null ? null : websiteUrl.trim());
        return save(actor, settings, "Updated company information.");
    }

    /**
     * Update contact information.
     */
    @Transactional
    public SystemSettings updateContactInfo(String companyEmail, String companyPhone, String companyAddress) {
        var actor = adminAccess.requireAdministrator("SYSTEM_SETTINGS_MANAGE");
        if (companyEmail == null || companyEmail.length() > 150 || !companyEmail.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Enter a valid support email address.");
        maxLength(companyPhone, 20, "Phone number"); maxLength(companyAddress, 2000, "Office address");
        SystemSettings settings = lockedSettings();
        settings.setCompanyEmail(companyEmail.trim().toLowerCase(java.util.Locale.ROOT));
        settings.setCompanyPhone(companyPhone);
        settings.setCompanyAddress(companyAddress);
        return save(actor, settings, "Updated public contact details.");
    }

    /**
     * Update business hours (stored as JSON string).
     */
    @Transactional
    public SystemSettings updateBusinessHours(String businessHoursJson) {
        var actor = adminAccess.requireAdministrator("SYSTEM_SETTINGS_MANAGE");
        if (businessHoursJson == null || businessHoursJson.isBlank()) throw new IllegalArgumentException("Enter business hours or state that opening hours are available on request.");
        maxLength(businessHoursJson, 4000, "Business hours");
        String hours = businessHoursJson.trim();
        if (hours.startsWith("{") || hours.startsWith("[")) {
            try {
                var schedule = json.readTree(hours);
                if (!schedule.isObject() || schedule.isEmpty()) throw new IllegalArgumentException("Use a JSON object with weekday names and opening hours.");
                var fields = schedule.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (!java.util.Set.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").contains(field.getKey())
                            || !field.getValue().isTextual() || field.getValue().asText().isBlank() || field.getValue().asText().length() > 150) {
                        throw new IllegalArgumentException("Use lowercase weekday names with readable opening hours or Closed.");
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                throw new IllegalArgumentException("Business hours JSON is invalid. Correct it or enter a readable text schedule.");
            }
        }
        SystemSettings settings = lockedSettings();
        settings.setBusinessHours(businessHoursJson);
        return save(actor, settings, "Updated business hours.");
    }

    /**
     * Update general system settings.
     */
    @Transactional
    public SystemSettings updateGeneralSettings(String currency, String timezone) {
        var actor = adminAccess.requireAdministrator("SYSTEM_SETTINGS_MANAGE");
        try { java.util.Currency.getInstance(currency); java.time.ZoneId.of(timezone); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Choose a valid currency code and time zone."); }
        if (!"USD".equals(currency))
            throw new IllegalArgumentException("This release supports USD prices and payments only. Currency conversion is not implemented; changing the code would not convert existing amounts.");
        maxLength(timezone, 50, "Time zone");
        SystemSettings settings = lockedSettings();
        if (!"USD".equals(settings.getCurrency()))
            throw new IllegalStateException("The recorded currency does not match this release's USD prices and payments. Review the existing catalogue and financial records before changing settings; no amounts have been converted.");
        settings.setCurrency(currency);
        settings.setTimezone(timezone);
        return save(actor, settings, "Updated time zone metadata; USD currency retained.");
    }

    /**
     * Update website status (ACTIVE / MAINTENANCE).
     */
    @Transactional
    public SystemSettings updateWebsiteStatus(String websiteStatus, String maintenanceMessage) {
        var actor = adminAccess.requireAdministrator("SYSTEM_SETTINGS_MANAGE");
        if (!"ACTIVE".equals(websiteStatus) && !"MAINTENANCE".equals(websiteStatus)) throw new IllegalArgumentException("Choose Active or Maintenance mode.");
        maxLength(maintenanceMessage, 2000, "Maintenance message");
        if ("MAINTENANCE".equals(websiteStatus) && (maintenanceMessage == null || maintenanceMessage.isBlank())) throw new IllegalArgumentException("Enter a maintenance message for visitors.");
        SystemSettings settings = lockedSettings();
        settings.setWebsiteStatus(websiteStatus);
        settings.setMaintenanceMessage(maintenanceMessage);
        return save(actor, settings, "Changed website mode to " + websiteStatus + ".");
    }

    private SystemSettings lockedSettings() {
        var rows = settingsRepository.lockSettings();
        if (rows.size() > 1) throw new IllegalStateException("Multiple system settings records exist. Ask the database administrator to review them before changing settings.");
        return rows.isEmpty() ? getSettings() : rows.get(0);
    }

    private SystemSettings save(com.travelgo.entity.User actor, SystemSettings settings, String description) {
        var saved = settingsRepository.save(settings);
        adminAccess.record(actor, "UPDATE_SETTINGS", "SystemSettings", saved.getId(), description);
        return saved;
    }

    private void validateCompany(String name, String description) {
        if (name == null || name.isBlank() || name.length() > 200) throw new IllegalArgumentException("Enter an agency name of up to 200 characters.");
        maxLength(description, 4000, "Company description");
    }

    private void maxLength(String text, int maximum, String label) {
        if (text != null && text.length() > maximum) throw new IllegalArgumentException(label + " must be at most " + maximum + " characters.");
    }

    private void validateWebsiteUrl(String websiteUrl) {
        if (websiteUrl == null || websiteUrl.isBlank()) return;
        maxLength(websiteUrl, 255, "Website address");
        try {
            var uri = java.net.URI.create(websiteUrl.trim());
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Enter an http or https website address without embedded credentials or a fragment.");
        }
    }

    /**
     * Check if the website is in maintenance mode.
     */
    @Transactional // First-time getSettings initialization may take row locks and insert defaults.
    public boolean isMaintenanceMode() {
        return "MAINTENANCE".equals(getSettings().getWebsiteStatus());
    }
}
