package com.travelgo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ContentSecurityPolicyConfiguration {
    @Bean public ContentSecurityPolicyFilter contentSecurityPolicyFilter(
            @Value("${travelgo.security.csp.enabled:true}") boolean enabled,
            @Value("${travelgo.public-base-url:http://localhost:8081}") String publicBaseUrl) {
        return new ContentSecurityPolicyFilter(enabled, publicBaseUrl);
    }

    @Bean public FilterRegistrationBean<ContentSecurityPolicyFilter> contentSecurityPolicyRegistration(
            ContentSecurityPolicyFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        // SecurityConfig executes this before security headers, CSRF and authentication short circuits.
        registration.setEnabled(false);
        return registration;
    }
}
