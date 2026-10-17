package com.travelgo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthenticationAbuseProperties.class)
public class AuthenticationAbuseConfiguration {
    @Bean public AuthenticationRequestLimiter authenticationRequestLimiter(AuthenticationAbuseProperties properties,
            @org.springframework.beans.factory.annotation.Value("${travelgo.security.auth-abuse.store:memory}") String store,
            @org.springframework.beans.factory.annotation.Value("${travelgo.security.auth-abuse.hmac-key:}") String key,
            org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        if ("memory".equals(store)) return new AuthenticationRequestLimiter(properties);
        if ("jdbc".equals(store)) return new SharedAuthenticationRequestLimiter(properties,key,jdbc,manager);
        throw new IllegalStateException("Authentication request store must be memory or jdbc.");
    }

    @Bean public AuthenticationAbuseFilter authenticationAbuseFilter(AuthenticationRequestLimiter limiter) {
        return new AuthenticationAbuseFilter(limiter);
    }

    @Bean public FilterRegistrationBean<AuthenticationAbuseFilter> authenticationAbuseFilterRegistration(
            AuthenticationAbuseFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        // SecurityConfig supplies the one intended execution point, after CSRF protection.
        registration.setEnabled(false);
        return registration;
    }
}
