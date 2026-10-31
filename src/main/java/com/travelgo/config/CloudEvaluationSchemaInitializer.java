package com.travelgo.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Adds the sole static row required by the durable request limiter after a guarded fresh-schema build. */
@Component
@Profile(CloudEvaluationSchemaInitializationGate.PROFILE)
@Order(100)
final class CloudEvaluationSchemaInitializer implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    CloudEvaluationSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void run(String... args) {
        int written = jdbc.update("INSERT INTO authentication_rate_cells(opaque_key,admitted_count,expires_at_ms) VALUES(?,0,0)",
                SharedAuthenticationRequestLimiter.GUARD);
        if (written != 1) throw new IllegalStateException("Hosted demonstration authentication guard could not be initialized.");
    }
}
