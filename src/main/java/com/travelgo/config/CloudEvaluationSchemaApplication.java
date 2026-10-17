package com.travelgo.config;

import com.travelgo.entity.User;
import com.travelgo.repository.UserRepository;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A non-web schema setup context with no application component scan or provider services. */
@Configuration(proxyBeanMethods = false)
@Profile("cloud-evaluation-init")
@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
@EntityScan(basePackageClasses = User.class)
@EnableJpaRepositories(basePackageClasses = UserRepository.class)
@Import({DataInitializer.class, CloudEvaluationSchemaInitializer.class})
public class CloudEvaluationSchemaApplication {
    @Bean
    PasswordEncoder schemaInitializationPasswordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
