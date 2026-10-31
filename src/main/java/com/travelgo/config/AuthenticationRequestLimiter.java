package com.travelgo.config;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bounded, atomic fixed windows for one application process. Limits follow the network source,
 * not the HTTP session; a global victim-account lockout is deliberately avoided.
 * Distributed/durable protection belongs at the trusted deployment edge or a shared store.
 */
public class AuthenticationRequestLimiter {
    public enum RequestKind { LOGIN, REGISTRATION, EMAIL_REQUEST, TOKEN_SUBMISSION }
    public record Decision(boolean allowed, long retryAfterSeconds) {}
    protected record Budget(int limit, long windowMillis) {}
    protected record Rule(String key, Budget budget) {}
    private static final class Bucket {
        final String key;
        final long expiresAt;
        int admitted;
        Bucket(String key, long expiresAt) { this.key = key; this.expiresAt = expiresAt; }
    }

    private final AuthenticationAbuseProperties properties;
    private final LongSupplier monotonicMillis;
    private final byte[] keySecret = new byte[32];
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final PriorityQueue<Bucket> expiry = new PriorityQueue<>(Comparator.comparingLong(b -> b.expiresAt));

    public AuthenticationRequestLimiter(AuthenticationAbuseProperties properties) {
        this(properties, () -> System.nanoTime() / 1_000_000L);
    }

    AuthenticationRequestLimiter(AuthenticationAbuseProperties properties, LongSupplier monotonicMillis) {
        this(properties, monotonicMillis, null);
    }

    protected AuthenticationRequestLimiter(AuthenticationAbuseProperties properties, byte[] sharedKey) {
        this(properties, () -> System.nanoTime() / 1_000_000L, sharedKey);
    }

    private AuthenticationRequestLimiter(AuthenticationAbuseProperties properties, LongSupplier monotonicMillis, byte[] sharedKey) {
        this.properties = properties;
        this.monotonicMillis = monotonicMillis;
        if (sharedKey == null) new SecureRandom().nextBytes(keySecret);
        else {
            if (sharedKey.length != keySecret.length) throw new IllegalArgumentException("Authentication store key is invalid.");
            System.arraycopy(sharedKey,0,keySecret,0,keySecret.length);
        }
    }

    public Decision acquire(RequestKind kind, String remoteAddress, String email) {
        if (!properties.enabled()) return new Decision(true, 0);
        return acquire(rulesFor(kind, remoteAddress, email));
    }

    protected List<Rule> rulesFor(RequestKind kind, String remoteAddress, String email) {
        String source = boundedIdentity(remoteAddress, 64);
        String account = boundedIdentity(email, 150);
        List<Rule> rules = new ArrayList<>(2);
        switch (kind) {
            case LOGIN -> {
                rules.add(rule("login-ip", source, "", properties.loginIpLimit(), properties.loginWindowSeconds()));
                rules.add(rule("login-account", source, account, properties.loginAccountLimit(), properties.loginWindowSeconds()));
            }
            case REGISTRATION -> {
                rules.add(rule("registration-ip", source, "", properties.registrationIpLimit(), properties.registrationWindowSeconds()));
                rules.add(rule("registration-account", source, account, properties.registrationAccountLimit(), properties.registrationWindowSeconds()));
            }
            case EMAIL_REQUEST -> {
                // Password-reset and verification-resend requests share budgets, preventing endpoint switching.
                rules.add(rule("email-ip", source, "", properties.emailIpLimit(), properties.emailIpWindowSeconds()));
                rules.add(rule("email-account", source, account, properties.emailAccountLimit(), properties.emailAccountWindowSeconds()));
            }
            case TOKEN_SUBMISSION -> rules.add(rule("token-ip", source, "", properties.tokenIpLimit(), properties.tokenWindowSeconds()));
        }
        return rules;
    }

    private synchronized Decision acquire(List<Rule> rules) {
        long now = monotonicMillis.getAsLong();
        while (!expiry.isEmpty() && expiry.peek().expiresAt <= now) {
            Bucket expired = expiry.remove();
            buckets.remove(expired.key, expired);
        }
        int needed = 0;
        long waitMillis = 0;
        for (Rule rule : rules) {
            Bucket current = buckets.get(rule.key());
            if (current == null) needed++;
            else if (current.admitted >= rule.budget().limit()) {
                waitMillis = Math.max(waitMillis, current.expiresAt - now);
            }
        }
        if (waitMillis > 0) return denied(waitMillis);
        if (buckets.size() + needed > properties.maximumBuckets()) {
            // Evicting a live bucket would let new identities reset existing limits. Deny instead.
            return denied(expiry.isEmpty() ? 1000 : expiry.peek().expiresAt - now);
        }
        for (Rule rule : rules) {
            Bucket current = buckets.get(rule.key());
            if (current == null) {
                current = new Bucket(rule.key(), now + rule.budget().windowMillis());
                buckets.put(current.key, current);
                expiry.add(current);
            }
            current.admitted++;
        }
        return new Decision(true, 0);
    }

    protected static Decision denied(long waitMillis) {
        return new Decision(false, Math.max(1, (waitMillis + 999) / 1000));
    }

    private Rule rule(String scope, String source, String account, int limit, long windowSeconds) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret, "HmacSHA256"));
            String key = HexFormat.of().formatHex(mac.doFinal(
                    (scope + '\0' + source + '\0' + account).getBytes(StandardCharsets.UTF_8)));
            return new Rule(key, new Budget(limit, windowSeconds * 1000));
        } catch (GeneralSecurityException unavailable) {
            // Never silently disable protection if the required JVM crypto primitive is unavailable.
            throw new IllegalStateException("Authentication abuse protection is unavailable.", unavailable);
        }
    }

    private static String boundedIdentity(String value, int maximumLength) {
        if (value == null || value.length() > maximumLength || value.isBlank()) return "<invalid>";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    synchronized int trackedBuckets() { return buckets.size(); }
}
