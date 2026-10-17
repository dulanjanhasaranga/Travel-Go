package com.travelgo.security;

import com.travelgo.config.StaffMfaProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.stereotype.Component;

/** RFC 6238/HOTP; the AES-GCM context binds each protected secret to its account. */
@Component
public class StaffMfaCrypto {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final SecureRandom random = new SecureRandom();
    private final byte[] encryptionKey;
    public StaffMfaCrypto(StaffMfaProperties properties) {
        properties.validate();
        encryptionKey = properties.isEnabled() ? Base64.getDecoder().decode(properties.getEncryptionKey()) : null;
    }
    public String newSecret() { byte[] bytes = new byte[20]; random.nextBytes(bytes); return base32(bytes); }
    public String newRecoveryCode() {
        byte[] bytes = new byte[20]; random.nextBytes(bytes);
        return base32(bytes).replaceAll("(.{4})(?!$)", "$1-");
    }
    public String protect(Long userId, String secret) {
        if (encryptionKey == null) throw unavailable();
        byte[] iv = new byte[12]; random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(context(userId));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException failure) { throw unavailable(); }
    }
    public String reveal(Long userId, String value) {
        if (encryptionKey == null || value == null || value.length() > 256) throw unavailable();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (bytes.length < 29) throw unavailable();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 0, 12)));
            cipher.updateAAD(context(userId));
            String secret = new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.US_ASCII);
            if (!secret.matches("[A-Z2-7]{32}")) throw unavailable();
            return secret;
        } catch (GeneralSecurityException | IllegalArgumentException failure) { throw unavailable(); }
    }
    private static byte[] context(Long userId) { return ("TravelGO:staff-totp:v1:" + userId).getBytes(StandardCharsets.US_ASCII); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Staff authentication is temporarily unavailable."); }
    public String recoveryHash(Long userId, String code) {
        String compact = code == null ? "" : code.replace("-", "").toUpperCase(Locale.ROOT);
        if (!compact.matches("[A-Z2-7]{32}")) return "";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    ("TravelGO:staff-recovery:v1:" + userId + ":" + compact).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw unavailable(); }
    }
    public long acceptedCounter(String secret, String code, Instant now, long lastCounter) {
        if (code == null || !code.matches("[0-9]{6}")) return -1;
        long current = now.getEpochSecond() / 30;
        // Search all allowed steps, even if a counter is already spent. Never accept a replay.
        long accepted = -1;
        for (long counter = Math.max(0, current - 1); counter <= current + 1; counter++) {
            boolean matches = MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII), totp(secret, counter, 6).getBytes(StandardCharsets.US_ASCII));
            if (matches && counter > lastCounter) accepted = counter;
        }
        return accepted;
    }
    public static String totp(String secret, long counter, int digits) {
        if (counter < 0 || (digits != 6 && digits != 8)) throw new IllegalArgumentException("Invalid token parameters.");
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] result = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = result[result.length - 1] & 15;
            int value = ((result[offset] & 127) << 24) | ((result[offset + 1] & 255) << 16)
                    | ((result[offset + 2] & 255) << 8) | (result[offset + 3] & 255);
            return String.format(Locale.ROOT, "%0" + digits + "d", value % (digits == 6 ? 1000000 : 100000000));
        } catch (GeneralSecurityException impossible) { throw unavailable(); }
    }
    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder(); int buffer = 0, bits = 0;
        for (byte item : bytes) {
            buffer = (buffer << 8) | (item & 255); bits += 8;
            while (bits >= 5) { bits -= 5; out.append(BASE32.charAt((buffer >>> bits) & 31)); }
        }
        if (bits > 0) out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }
    static byte[] decodeBase32(String value) {
        if (value == null || !value.matches("[A-Z2-7]+")) throw new IllegalArgumentException("Invalid token secret.");
        var out = new java.io.ByteArrayOutputStream(); int buffer = 0, bits = 0;
        for (char item : value.toCharArray()) {
            buffer = (buffer << 5) | BASE32.indexOf(item); bits += 5;
            if (bits >= 8) { bits -= 8; out.write((buffer >>> bits) & 255); }
        }
        return out.toByteArray();
    }
}
