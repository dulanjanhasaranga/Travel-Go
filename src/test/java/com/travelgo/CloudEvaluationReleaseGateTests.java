package com.travelgo;

import com.travelgo.config.CloudEvaluationReleaseGate;
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

/** Configuration-only tests: no Aiven, Supabase, Brevo, Cloudmersive, database, or web requests. */
class CloudEvaluationReleaseGateTests {
    final CloudEvaluationReleaseGate gate = new CloudEvaluationReleaseGate();
    MockEnvironment environment;

    @BeforeEach void configuration() {
        environment = new MockEnvironment();
        environment.setActiveProfiles("cloud-evaluation");
        Map.ofEntries(
                Map.entry("travelgo.cloud-evaluation.release-acceptance", "SYNTHETIC_DEMO_ONLY"),
                Map.entry("spring.datasource.url", "jdbc:mysql://travelgo-mysql-travelgo.aivencloud.com:17758/defaultdb?sslMode=VERIFY_IDENTITY&serverTimezone=UTC"),
                Map.entry("spring.datasource.username", "travelgo_demo_app"), Map.entry("spring.datasource.password", "Synthetic-db-password123!"),
                Map.entry("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl", "file:/tmp/travelgo-aiven/truststore.p12"),
                Map.entry("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreType", "PKCS12"),
                Map.entry("spring.datasource.hikari.data-source-properties.trustCertificateKeyStorePassword", "Synthetic-trust-password123!"),
                Map.entry("spring.datasource.hikari.data-source-properties.fallbackToSystemTrustStore", "false"),
                Map.entry("travelgo.public-base-url", "https://travelgo-demo.onrender.com"),
                Map.entry("spring.jpa.hibernate.ddl-auto", "validate"), Map.entry("spring.sql.init.mode", "never"),
                Map.entry("spring.jpa.mapping-resources", "META-INF/cloud-evaluation-orm.xml"),
                Map.entry("travelgo.data-initializer.enabled", "false"), Map.entry("travelgo.demo.enabled", "false"),
                Map.entry("server.address", "0.0.0.0"), Map.entry("server.port", "8081"), Map.entry("server.forward-headers-strategy", "native"),
                Map.entry("server.servlet.session.cookie.secure", "true"), Map.entry("server.servlet.session.cookie.http-only", "true"),
                Map.entry("server.error.include-message", "never"), Map.entry("server.error.include-stacktrace", "never"),
                Map.entry("travelgo.security.csp.enabled", "true"), Map.entry("travelgo.security.auth-abuse.enabled", "true"),
                Map.entry("travelgo.security.auth-abuse.store", "jdbc"), Map.entry("travelgo.security.auth-abuse.hmac-key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
                Map.entry("travelgo.security.staff-mfa.enabled", "true"), Map.entry("travelgo.security.staff-mfa.staff-mandatory", "true"),
                Map.entry("travelgo.security.staff-mfa.encryption-key", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="),
                Map.entry("travelgo.mail.enabled", "true"), Map.entry("travelgo.mail.dispatch-enabled", "true"),
                Map.entry("travelgo.mail.transport", "brevo-api"), Map.entry("travelgo.mail.from", "no-reply@travelgo.demo"),
                Map.entry("travelgo.mail.brevo-api-key", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), Map.entry("travelgo.mail.feedback.brevo-enabled", "false"),
                Map.entry("travelgo.payments.simulated", "true"), Map.entry("travelgo.stripe.enabled", "false"), Map.entry("travelgo.payhere.enabled", "false"),
                Map.entry("travelgo.auth.google.enabled", "false"), Map.entry("travelgo.bookings.require-published-policy", "true"),
                Map.entry("travelgo.bookings.require-supplier-confirmation", "true"),
                Map.entry("travelgo.documents.storage-backend", "supabase"), Map.entry("travelgo.upload-directory", "/tmp/travelgo-document-fallback"),
                Map.entry("travelgo.documents.max-file-bytes", "3000000"), Map.entry("travelgo.documents.max-image-pixels", "8000000"),
                Map.entry("spring.servlet.multipart.max-file-size", "3000000B"), Map.entry("spring.servlet.multipart.max-request-size", "3500000B"),
                Map.entry("travelgo.documents.supabase.project-url", "https://oiwnhoimgzoxamfppmwc.supabase.co"),
                Map.entry("travelgo.documents.supabase.bucket", "travelgo-documents"), Map.entry("travelgo.documents.supabase.service-key", "sb_secret_abcdefghijklmnopqrstuvwx"),
                Map.entry("travelgo.documents.supabase.connect-timeout-ms", "3000"), Map.entry("travelgo.documents.supabase.request-timeout-ms", "15000"),
                Map.entry("travelgo.documents.scan.enabled", "true"), Map.entry("travelgo.documents.scan.backend", "cloudmersive"),
                Map.entry("travelgo.documents.scan.max-bytes", "3000000"), Map.entry("travelgo.documents.cloudmersive.api-key", "abcdefghijklmnopqrst"),
                Map.entry("travelgo.documents.cloudmersive.connect-timeout-ms", "2000"), Map.entry("travelgo.documents.cloudmersive.request-timeout-ms", "15000"),
                Map.entry("travelgo.catalogue.install", "false"), Map.entry("travelgo.catalogue.restore-original", "false")
        ).forEach(environment::setProperty);
    }

    void validate() { gate.postProcessEnvironment(environment, null); }

    @Test void validIsolatedHostedDemoSettingsNeedNoConnectionsOrWrites() {
        assertDoesNotThrow(this::validate);
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "test", "prod", "demo"})
    void existingProfilesRemainUnderTheirOwnGuards(String profile) {
        var other = new MockEnvironment(); other.setActiveProfiles(profile);
        assertDoesNotThrow(() -> gate.postProcessEnvironment(other, null));
    }

    @Test void cloudEvaluationCannotBeMixedWithAnyOtherProfile() {
        environment.setActiveProfiles("cloud-evaluation", "prod");
        assertThrows(IllegalStateException.class, this::validate);
    }

    @ParameterizedTest @ValueSource(strings = {
            "travelgo.cloud-evaluation.release-acceptance", "spring.jpa.hibernate.ddl-auto", "spring.jpa.mapping-resources", "travelgo.data-initializer.enabled",
            "server.address", "server.port", "server.servlet.session.cookie.secure", "travelgo.security.auth-abuse.store",
            "travelgo.mail.transport", "travelgo.payments.simulated", "travelgo.documents.storage-backend", "travelgo.documents.scan.backend"})
    void unsafeReleaseSwitchesAreRejectedBeforeAnyProviderCanRun(String setting) {
        environment.getPropertySources().addFirst(new MapPropertySource("override", Map.of(setting, "unsafe")));
        assertThrows(IllegalStateException.class, this::validate);
    }

    @Test void credentialsAndUnsafeJdbcParametersAreNotReflectedInDiagnostics() {
        environment.setProperty("spring.datasource.url", "jdbc:mysql://travelgo-mysql-travelgo.aivencloud.com:17758/defaultdb?sslMode=VERIFY_IDENTITY&password=private-value");
        Exception failure = assertThrows(IllegalStateException.class, this::validate);
        assertFalse(failure.toString().contains("private-value")); assertNull(failure.getCause());
    }

    @Test void alternateDatasourcePoolBindingsAreRejected() {
        environment.setProperty("spring.datasource.hikari.data-source-properties.allowLoadLocalInfile", "true");
        assertThrows(IllegalStateException.class, this::validate);
    }

    @Test void publicOriginAndPrivateDocumentStorageMustBeConfigured() {
        environment.setProperty("travelgo.public-base-url", "http://localhost:8081");
        assertThrows(IllegalStateException.class, this::validate);
        environment.setProperty("travelgo.public-base-url", "https://travelgo-demo.onrender.com");
        environment.setProperty("travelgo.documents.supabase.service-key", "private-value");
        Exception failure = assertThrows(IllegalStateException.class, this::validate);
        assertFalse(failure.toString().contains("private-value"));
    }

    @Test void registrationStopsBeforeAnyBeanOrWebServerIsCreatedWhenSettingsAreIncomplete() {
        Fixture.initialized.set(false);
        var application = new SpringApplication(Fixture.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false); application.setRegisterShutdownHook(false);
        assertThrows(IllegalStateException.class, () -> application.run("--spring.profiles.active=cloud-evaluation", "--spring.config.import="));
        assertFalse(Fixture.initialized.get());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        static final AtomicBoolean initialized = new AtomicBoolean();
        @Bean String signal() { initialized.set(true); return "initialized"; }
    }
}
