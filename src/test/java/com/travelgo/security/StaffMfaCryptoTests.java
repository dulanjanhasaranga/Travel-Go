package com.travelgo.security;

import com.travelgo.config.StaffMfaProperties;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaffMfaCryptoTests {
    private StaffMfaProperties properties() {
        var p = new StaffMfaProperties(); p.setEnabled(true);
        p.setEncryptionKey(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        return p;
    }
    @Test void rfc6238Sha1EightDigitVectorsIncludingBeyond2038() {
        String secret = StaffMfaCrypto.base32("12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        long[] times = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
        String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i = 0; i < times.length; i++) assertEquals(expected[i], StaffMfaCrypto.totp(secret, times[i] / 30, 8));
    }
    @Test void encryptedSecretsAreRandomizedAuthenticatedAndBoundToTheirOwner() {
        var c = new StaffMfaCrypto(properties()); String secret = c.newSecret();
        String first = c.protect(11L, secret), second = c.protect(11L, secret);
        assertNotEquals(first, second); assertFalse(first.contains(secret)); assertEquals(secret, c.reveal(11L, first));
        assertThrows(IllegalStateException.class, () -> c.reveal(12L, first));
        byte[] changed = Base64.getUrlDecoder().decode(first); changed[20] ^= 1;
        assertThrows(IllegalStateException.class, () -> c.reveal(11L, Base64.getUrlEncoder().withoutPadding().encodeToString(changed)));
        var wrong = properties(); wrong.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        assertThrows(IllegalStateException.class, () -> new StaffMfaCrypto(wrong).reveal(11L, first));
    }
    @Test void codeWindowIsBoundedAndSpentCountersCannotReplay() {
        var c = new StaffMfaCrypto(properties()); String secret = c.newSecret(); Instant now = Instant.parse("2030-01-01T12:00:00Z");
        long counter = now.getEpochSecond() / 30; String token = StaffMfaCrypto.totp(secret, counter, 6);
        assertEquals(counter, c.acceptedCounter(secret, token, now, -1));
        assertEquals(-1, c.acceptedCounter(secret, token, now, counter));
        assertEquals(-1, c.acceptedCounter(secret, token, now.plusSeconds(90), -1));
        assertEquals(-1, c.acceptedCounter(secret, "1234567", now, -1));
        assertEquals(-1, c.acceptedCounter(secret, " 123456", now, -1));
    }
    @Test void recoveryCodesAreLongRandomHashesBoundToAccount() {
        var c = new StaffMfaCrypto(properties()); String first = c.newRecoveryCode(), second = c.newRecoveryCode();
        assertTrue(first.matches("(?:[A-Z2-7]{4}-){7}[A-Z2-7]{4}")); assertNotEquals(first, second);
        assertEquals(64, c.recoveryHash(1L, first).length());
        assertEquals(c.recoveryHash(1L, first), c.recoveryHash(1L, first.toLowerCase(java.util.Locale.ROOT).replace("-", "")));
        assertNotEquals(c.recoveryHash(1L, first), c.recoveryHash(2L, first));
        assertEquals("", c.recoveryHash(1L, "not-a-recovery-code"));
    }
    @Test void enabledConfigurationRejectsMissingMalformedOrWrongLengthKeysAndUnsafeLimits() {
        for (String key : new String[] {"", "placeholder", Base64.getEncoder().encodeToString(new byte[31])}) {
            var p = properties(); p.setEncryptionKey(key); assertThrows(IllegalStateException.class, () -> new StaffMfaCrypto(p));
        }
        var p = properties(); p.setStepUpSeconds(301); assertThrows(IllegalStateException.class, p::validate);
        p = properties(); p.setMaxAttempts(100); assertThrows(IllegalStateException.class, p::validate);
        var disabled = new StaffMfaProperties(); assertDoesNotThrow(() -> new StaffMfaCrypto(disabled));
        assertThrows(IllegalStateException.class, () -> new StaffMfaCrypto(disabled).protect(1L, "x"));
    }
}
