package com.travelgo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded request budgets for the local-memory or shared JDBC store. No account lookup is needed. */
@ConfigurationProperties("travelgo.security.auth-abuse")
public record AuthenticationAbuseProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20000") int maximumBuckets,
        @DefaultValue("60") int loginIpLimit,
        @DefaultValue("10") int loginAccountLimit,
        @DefaultValue("900") long loginWindowSeconds,
        @DefaultValue("10") int registrationIpLimit,
        @DefaultValue("3") int registrationAccountLimit,
        @DefaultValue("3600") long registrationWindowSeconds,
        @DefaultValue("20") int emailIpLimit,
        @DefaultValue("3600") long emailIpWindowSeconds,
        @DefaultValue("3") int emailAccountLimit,
        @DefaultValue("900") long emailAccountWindowSeconds,
        @DefaultValue("30") int tokenIpLimit,
        @DefaultValue("900") long tokenWindowSeconds) {

    public AuthenticationAbuseProperties {
        positiveBounded(maximumBuckets, 2, 1_000_000, "maximum-buckets");
        for (int limit : new int[] {loginIpLimit, loginAccountLimit, registrationIpLimit,
                registrationAccountLimit, emailIpLimit, emailAccountLimit, tokenIpLimit}) {
            positiveBounded(limit, 1, 1_000_000, "request limit");
        }
        for (long seconds : new long[] {loginWindowSeconds, registrationWindowSeconds,
                emailIpWindowSeconds, emailAccountWindowSeconds, tokenWindowSeconds}) {
            positiveBounded(seconds, 1, 86_400, "window seconds");
        }
    }

    private static void positiveBounded(long value, long minimum, long maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("Authentication abuse protection " + name
                    + " must be between " + minimum + " and " + maximum + ".");
        }
    }

    public static AuthenticationAbuseProperties defaults() {
        return new AuthenticationAbuseProperties(true, 20_000, 60, 10, 900,
                10, 3, 3600, 20, 3600, 3, 900, 30, 900);
    }
}
