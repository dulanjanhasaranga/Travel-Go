package com.travelgo.config;

import com.travelgo.service.BrevoTransactionalMailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** Explicit opt-in only. Spring Boot's SMTP sender remains the default. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "travelgo.mail.transport", havingValue = "brevo-api")
public class BrevoMailConfiguration {
    @Bean
    JavaMailSender brevoTransactionalMailSender(
            @Value("${travelgo.mail.brevo-api-key:}") String apiKey,
            @Value("${travelgo.public-base-url:http://localhost:8081}") String publicOrigin) {
        return new BrevoTransactionalMailSender(apiKey, publicOrigin);
    }
}
