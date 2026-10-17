package com.travelgo.config;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** Never contacts Aiven: the database inspection collaborator is replaced by a local fake. */
class CloudEvaluationSchemaInitializationGateTests {
    MockEnvironment environment;
    AtomicBoolean inspected;
    CloudEvaluationSchemaInitializationGate gate;

    @BeforeEach void configuration() {
        inspected = new AtomicBoolean();
        gate = new CloudEvaluationSchemaInitializationGate(settings -> inspected.set(true));
        environment = new MockEnvironment();
        environment.setActiveProfiles("cloud-evaluation-init");
        Map.ofEntries(
                Map.entry("travelgo.cloud-evaluation.release-acceptance", "SYNTHETIC_DEMO_ONLY"),
                Map.entry("travelgo.cloud-evaluation.schema-init", "CREATE_EMPTY_SCHEMA"),
                Map.entry("travelgo.cloud-evaluation.init-confirmation", "INITIALIZE_EMPTY_AIVEN_SCHEMA"),
                Map.entry("spring.datasource.url", "jdbc:mysql://travelgo-mysql-travelgo.aivencloud.com:17758/defaultdb?sslMode=VERIFY_IDENTITY&serverTimezone=UTC"),
                Map.entry("spring.datasource.username", "avnadmin"), Map.entry("spring.datasource.password", "Synthetic-admin-password123!"),
                Map.entry("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl", "file:/tmp/travelgo-aiven/truststore.p12"),
                Map.entry("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreType", "PKCS12"),
                Map.entry("spring.datasource.hikari.data-source-properties.trustCertificateKeyStorePassword", "Synthetic-trust-password123!"),
                Map.entry("spring.datasource.hikari.data-source-properties.fallbackToSystemTrustStore", "false"),
                Map.entry("spring.jpa.hibernate.ddl-auto", "create"), Map.entry("spring.sql.init.mode", "never"),
                Map.entry("travelgo.data-initializer.enabled", "true"), Map.entry("travelgo.demo.enabled", "false"),
                Map.entry("travelgo.bootstrap.admin.email", "admin@travelgo.demo"), Map.entry("travelgo.bootstrap.admin.password", "Synthetic-admin-password123!"),
                Map.entry("travelgo.mail.enabled", "false"), Map.entry("travelgo.mail.dispatch-enabled", "false"),
                Map.entry("travelgo.payments.simulated", "true"), Map.entry("travelgo.stripe.enabled", "false"),
                Map.entry("travelgo.payhere.enabled", "false"), Map.entry("travelgo.auth.google.enabled", "false"),
                Map.entry("travelgo.catalogue.install", "false"), Map.entry("travelgo.catalogue.restore-original", "false")
        ).forEach(environment::setProperty);
    }

    void validate() { gate.postProcessEnvironment(environment, null); }

    @Test void approvedEmptySchemaConfigurationInspectsBeforeHibernateCanCreateTables() {
        assertDoesNotThrow(this::validate);
        assertTrue(inspected.get());
    }

    @Test void inspectionFailureDoesNotExposeDatabaseDetails() {
        gate = new CloudEvaluationSchemaInitializationGate(settings -> { throw new IllegalStateException("private database detail"); });
        Exception failure = assertThrows(IllegalStateException.class, this::validate);
        assertFalse(failure.toString().contains("private database detail")); assertNull(failure.getCause());
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "test", "prod", "demo", "cloud-evaluation"})
    void otherProfilesRemainAvailable(String profile) {
        var other = new MockEnvironment(); other.setActiveProfiles(profile);
        assertDoesNotThrow(() -> gate.postProcessEnvironment(other, null));
    }

    @Test void initializerProfileCannotBeMixedWithAnyOtherProfile() {
        environment.setActiveProfiles("cloud-evaluation-init", "prod");
        assertThrows(IllegalStateException.class, this::validate);
        assertFalse(inspected.get());
    }

    @ParameterizedTest @ValueSource(strings = {
            "travelgo.cloud-evaluation.release-acceptance", "travelgo.cloud-evaluation.schema-init",
            "travelgo.cloud-evaluation.init-confirmation", "spring.jpa.hibernate.ddl-auto", "travelgo.data-initializer.enabled",
            "travelgo.mail.enabled", "travelgo.payments.simulated", "travelgo.catalogue.install"})
    void unsafeInitializationSwitchesFailBeforeDatabaseInspection(String setting) {
        environment.getPropertySources().addFirst(new MapPropertySource("override", Map.of(setting, "unsafe")));
        assertThrows(IllegalStateException.class, this::validate);
        assertFalse(inspected.get());
    }

    @Test void standardDatasourceAndHibernateAlternativesCannotBypassTheEmptySchemaCheck() {
        environment.setProperty("SPRING_DATASOURCE_URL", "jdbc:mysql://private-value");
        Exception failure = assertThrows(IllegalStateException.class, this::validate);
        assertFalse(failure.toString().contains("private-value")); assertFalse(inspected.get());
        environment = new MockEnvironment(); configuration();
        environment.setProperty("spring.jpa.properties.hibernate.hbm2ddl.auto", "create-drop");
        assertThrows(IllegalStateException.class, this::validate);
        assertFalse(inspected.get());
    }

    @Test void bootRegistrationStopsBeforeAnyBeanOrExternalConnectionWhenSettingsAreMissing() {
        Fixture.initialized.set(false);
        var application = new SpringApplication(Fixture.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false); application.setRegisterShutdownHook(false);
        assertThrows(IllegalStateException.class, () -> application.run("--spring.profiles.active=cloud-evaluation-init", "--spring.config.import="));
        assertFalse(Fixture.initialized.get());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        static final AtomicBoolean initialized = new AtomicBoolean();
        @Bean String signal() { initialized.set(true); return "initialized"; }
    }
}
