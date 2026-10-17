package com.travelgo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in locally; production validation requires both flags and a private encryption key. */
@ConfigurationProperties("travelgo.security.staff-mfa")
public class StaffMfaProperties {
    private boolean enabled;
    private boolean staffMandatory = true;
    private String encryptionKey = "";
    private int maxAttempts = 5;
    private int cooldownSeconds = 300;
    private int challengeSeconds = 600;
    private int sessionSeconds = 28800;
    private int stepUpSeconds = 300;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isStaffMandatory() { return staffMandatory; }
    public void setStaffMandatory(boolean value) { staffMandatory = value; }
    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String value) { encryptionKey = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = value; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(int value) { cooldownSeconds = value; }
    public int getChallengeSeconds() { return challengeSeconds; }
    public void setChallengeSeconds(int value) { challengeSeconds = value; }
    public int getSessionSeconds() { return sessionSeconds; }
    public void setSessionSeconds(int value) { sessionSeconds = value; }
    public int getStepUpSeconds() { return stepUpSeconds; }
    public void setStepUpSeconds(int value) { stepUpSeconds = value; }
    public void validate() {
        if (maxAttempts < 1 || maxAttempts > 10 || cooldownSeconds < 60 || cooldownSeconds > 3600
                || challengeSeconds < 60 || challengeSeconds > 900 || sessionSeconds < 300 || sessionSeconds > 28800
                || stepUpSeconds < 30 || stepUpSeconds > 300 || stepUpSeconds > sessionSeconds)
            throw new IllegalStateException("Staff authentication limits are invalid.");
        if (enabled) {
            byte[] key;
            try { key = java.util.Base64.getDecoder().decode(encryptionKey == null ? "" : encryptionKey); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("A private 32-byte staff MFA encryption key is required."); }
            if (key.length != 32) throw new IllegalStateException("A private 32-byte staff MFA encryption key is required.");
            java.util.Arrays.fill(key, (byte) 0);
        }
    }
}
