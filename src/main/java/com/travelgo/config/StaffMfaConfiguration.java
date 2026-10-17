package com.travelgo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StaffMfaProperties.class)
public class StaffMfaConfiguration {
    @Bean
    FilterRegistrationBean<StaffMfaGateFilter> staffMfaRegistration(StaffMfaGateFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // The security chain alone controls ordering and authentication.
        return registration;
    }
}
