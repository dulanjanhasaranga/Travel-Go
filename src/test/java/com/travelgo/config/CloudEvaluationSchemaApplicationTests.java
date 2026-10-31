package com.travelgo.config;

import com.travelgo.TravelGoApplication;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.SystemSettingsRepository;
import com.travelgo.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Uses only an isolated H2 database; environment gates are separately tested without Aiven. */
class CloudEvaluationSchemaApplicationTests {
    @Test void dedicatedNonWebContextCreatesSchemaAndOnlyTheRequestedAdministrator() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("cloud-evaluation-init"))
                .withUserConfiguration(CloudEvaluationSchemaApplication.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:schema-init-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa", "spring.datasource.password=",
                        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
                        "spring.sql.init.mode=never", "spring.main.web-application-type=none",
                        "travelgo.data-initializer.enabled=true",
                        "travelgo.bootstrap.admin.email=hosted-admin@travelgo.test",
                        "travelgo.bootstrap.admin.password=Synthetic-bootstrap-password-123!",
                        "travelgo.bootstrap.admin.name=Hosted Demo Administrator")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(CommandLineRunner.class)).hasSize(2);
                    for (CommandLineRunner runner : context.getBeanProvider(CommandLineRunner.class).orderedStream().toList()) {
                        try { runner.run(); }
                        catch (Exception failure) { throw new AssertionError("Schema setup runner failed", failure); }
                    }
                    var users = context.getBean(UserRepository.class);
                    assertThat(users.count()).isEqualTo(1);
                    var admin = users.findByEmailIgnoreCase("hosted-admin@travelgo.test").orElseThrow();
                    assertThat(admin.getRole().getRoleName()).isEqualTo("ADMIN");
                    assertThat(admin.isActive()).isTrue();
                    assertThat(context.getBean(PasswordEncoder.class).matches(
                            "Synthetic-bootstrap-password-123!", admin.getPassword())).isTrue();
                    assertThat(context.getBean(RoleRepository.class).findByRoleName("CUSTOMER")).isPresent();
                    assertThat(context.getBean(SystemSettingsRepository.class).count()).isEqualTo(1);
                    assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                            "SELECT COUNT(*) FROM authentication_rate_cells WHERE opaque_key=? AND admitted_count=0",
                            Long.class, SharedAuthenticationRequestLimiter.GUARD)).isEqualTo(1);
                    assertThat(context).doesNotHaveBean(SecurityConfig.class).doesNotHaveBean(WebSocketConfig.class);
                    assertThat(context).doesNotHaveBean("publicController").doesNotHaveBean("emailService")
                            .doesNotHaveBean("staffUpdatePublisher").doesNotHaveBean("documentStorageConfiguration")
                            .doesNotHaveBean("bookingHoldExpiryDispatcher");
                });
    }

    @Test void initializationActionWithoutReviewedProfileFailsBeforeOpeningApplicationContext() {
        assertThrows(IllegalStateException.class, () -> TravelGoApplication.main(new String[]{
                "--travelgo.cloud-evaluation.schema-init=CREATE_EMPTY_SCHEMA"}));
    }

    @Test void ordinaryProfileDoesNotActivateSchemaSetupConfiguration() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("mysql"))
                .withUserConfiguration(CloudEvaluationSchemaApplication.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(CloudEvaluationSchemaApplication.class)
                        .doesNotHaveBean(DataInitializer.class));
    }
}
