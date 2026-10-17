package com.travelgo;

import com.travelgo.config.ProductionEnvironmentValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Configuration-only tests: no database, seeded users, web server or external requests. */
class ProductionEnvironmentValidatorTests {
    @TempDir Path directory;
    MockEnvironment environment;
    final ProductionEnvironmentValidator validator = new ProductionEnvironmentValidator();

    @BeforeEach void configuration() throws Exception {
        Path documents = Files.createDirectory(directory.resolve("documents"));
        Path logs = Files.createDirectory(directory.resolve("logs"));
        environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        Map.ofEntries(
                Map.entry("spring.datasource.url", "jdbc:mysql://database.agency.net:3306/travelgo?sslMode=VERIFY_IDENTITY"),
                Map.entry("spring.datasource.username", "travelgo_application"), Map.entry("spring.datasource.password", "Synthetic-db-password123!"),
                Map.entry("travelgo.public-base-url", "https://travel.agency.net"), Map.entry("spring.jpa.hibernate.ddl-auto", "validate"),
                Map.entry("spring.sql.init.mode", "never"),
                Map.entry("travelgo.data-initializer.enabled", "false"),
                Map.entry("server.servlet.session.cookie.secure", "true"), Map.entry("server.servlet.session.cookie.http-only", "true"),
                Map.entry("server.error.include-message", "never"), Map.entry("server.error.include-stacktrace", "never"),
                Map.entry("travelgo.demo.enabled", "false"), Map.entry("travelgo.bookings.require-published-policy", "true"),
                Map.entry("travelgo.bookings.require-supplier-confirmation", "true"), Map.entry("travelgo.payments.simulated", "true"),
                Map.entry("travelgo.stripe.enabled", "false"), Map.entry("travelgo.upload-directory", documents.toString()),
                Map.entry("travelgo.documents.scan.enabled", "true"),
                Map.entry("travelgo.security.csp.enabled", "true"),
                Map.entry("travelgo.security.auth-abuse.enabled", "true"),
                Map.entry("travelgo.security.auth-abuse.store", "jdbc"),
                Map.entry("travelgo.security.auth-abuse.hmac-key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
                Map.entry("travelgo.security.staff-mfa.enabled", "true"),
                Map.entry("travelgo.security.staff-mfa.staff-mandatory", "true"),
                Map.entry("travelgo.security.staff-mfa.encryption-key", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="),
                Map.entry("logging.file.name", logs.resolve("travelgo.log").toString()),
                Map.entry("logging.logback.rollingpolicy.file-name-pattern", logs.resolve("travelgo.%d{yyyy-MM-dd}.%i.log.gz").toString())
        ).forEach(environment::setProperty);
    }
    void validate() { validator.postProcessEnvironment(environment, null); }

    @Test void validEffectiveProductionSettingsNeedNoConnectionsOrWrites() throws Exception {
        assertDoesNotThrow(this::validate);
        try (var files = Files.list(directory.resolve("documents"))) { assertEquals(0, files.count()); }
        assertFalse(Files.exists(directory.resolve("logs/travelgo.log")));
    }
    @Test void productionRequiresExplicitLocalDocumentScanningWithoutContactingDaemon() {
        environment.setProperty("travelgo.documents.scan.enabled", "false");
        assertThrows(IllegalStateException.class, this::validate);
        environment.setProperty("travelgo.documents.scan.enabled", "true");
        environment.setProperty("travelgo.documents.scan.host", "::1");
        assertDoesNotThrow(this::validate);
        environment.setProperty("travelgo.documents.scan.host", "scanner.private-value.invalid");
        Exception failure = assertThrows(IllegalStateException.class, this::validate);
        assertFalse(failure.toString().contains("private-value")); assertNull(failure.getCause());
    }
    @ParameterizedTest @ValueSource(strings={"travelgo.documents.scan.port", "travelgo.documents.scan.connect-timeout-ms",
            "travelgo.documents.scan.read-timeout-ms", "travelgo.documents.scan.max-bytes"})
    void invalidScannerLimitsFailBeforeBeanCreation(String setting) {
        environment.setProperty(setting, "0");
        assertThrows(IllegalStateException.class, this::validate);
    }
    @Test void nativeWindowsScannerCannotReplaceTheProductionClamdRequirement() {
        environment.setProperty("travelgo.documents.scan.backend", "windows-defender");
        Exception failure = assertThrows(IllegalStateException.class, this::validate);
        assertNull(failure.getCause());
        environment.setProperty("travelgo.documents.scan.backend", "clamd");
        assertDoesNotThrow(this::validate);
    }
    @Test void optionalCloudStorageCannotReplaceTheAcceptedProductionFilesystem() {
        environment.setProperty("travelgo.documents.storage-backend", "supabase");
        Exception failure=assertThrows(IllegalStateException.class, this::validate);
        assertNull(failure.getCause());
        environment.setProperty("travelgo.documents.storage-backend", "filesystem");
        assertDoesNotThrow(this::validate);
    }
    @Test void existingDevelopmentAndIsolatedTestProfilesRemainAvailable() {
        environment = new MockEnvironment(); environment.setActiveProfiles("mysql"); assertDoesNotThrow(this::validate);
        environment.setActiveProfiles("test", "demo", "production"); assertDoesNotThrow(this::validate);
    }
    @ParameterizedTest @ValueSource(strings={"test", "mysql-test", "demo"})
    void prodCannotBeCombinedWithFixtureOrDemoProfiles(String profile) {
        environment.setActiveProfiles("prod", profile); assertThrows(IllegalStateException.class, this::validate);
    }
    @ParameterizedTest @ValueSource(strings={
            "jdbc:h2:mem:unsafe", "jdbc:mysql://user:private-value@database.agency.net/travelgo",
            "jdbc:mysql://one,two/travelgo", "jdbc:mysql://database.agency.net/one/two", "jdbc:mysql://database.agency.net:99999/travelgo",
            "jdbc:mysql://database.agency.net/travelgo?password=private-value", "jdbc:mysql://database.agency.net/travelgo?%75ser=private-value",
            "jdbc:mysql://database.agency.net/travelgo?sslMode=VERIFY_IDENTITY&sslMode=DISABLED",
            "jdbc:mysql://database.agency.net/travelgo?sslMode=VERIFY_IDENTITY&allowLoadLocalInfile=true",
            "jdbc:mysql://database.agency.net/travelgo?sslMode=VERIFY_IDENTITY&allowLoadLocalInfile=YES",
            "jdbc:mysql://database.agency.net/travelgo#private-value"
    })
    void unsafeJdbcUrlsAreRejectedWithoutEchoingCredentials(String url) {
        environment.setProperty("spring.datasource.url", url);
        Exception failure = assertThrows(IllegalStateException.class, this::validate);
        assertFalse(failure.toString().contains("private-value")); assertNull(failure.getCause());
    }
    @Test void remoteMysqlRequiresIdentityVerificationButLocalOperatorMysqlRemainsCompatible() {
        environment.setProperty("spring.datasource.url", "jdbc:mysql://database.agency.net/travelgo?sslMode=REQUIRED");
        assertThrows(IllegalStateException.class, this::validate);
        environment.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1/travelgo?useSSL=false"); assertDoesNotThrow(this::validate);
    }
    @ParameterizedTest @ValueSource(strings={"root", " ROOT "})
    void privilegedAccountNamesAreRejected(String name) {
        environment.setProperty("spring.datasource.username", name); assertThrows(IllegalStateException.class, this::validate);
    }
    @Test void unresolvedAndExampleSecretsProduceOnlyFixedDiagnostics() {
        environment.setProperty("spring.datasource.password", "REPLACE_IN_SECRET_MANAGER"); assertThrows(IllegalStateException.class, this::validate);
        environment.setProperty("spring.datasource.password", "${missing-private-value}");
        Exception failure = assertThrows(IllegalStateException.class, this::validate);
        assertFalse(failure.toString().contains("missing-private-value")); assertNull(failure.getCause());
    }
    @ParameterizedTest @ValueSource(strings={"http://travel.agency.net", "https://localhost", "https://travelgo.invalid", "https://travelgo.test",
            "https://user:private-value@travel.agency.net", "https://travel.agency.net/path", "https://travel.agency.net?token=private-value"})
    void realHttpsOriginIsRequiredEvenWhenProvidersAreDisabled(String url) {
        environment.setProperty("travelgo.public-base-url", url);
        Exception failure = assertThrows(IllegalStateException.class, this::validate); assertFalse(failure.toString().contains("private-value"));
    }
    @ParameterizedTest @ValueSource(strings={"spring.jpa.hibernate.ddl-auto", "spring.sql.init.mode", "travelgo.data-initializer.enabled", "server.servlet.session.cookie.secure", "server.servlet.session.cookie.http-only",
            "server.error.include-message", "server.error.include-stacktrace", "travelgo.demo.enabled", "travelgo.bookings.require-published-policy",
            "travelgo.bookings.require-supplier-confirmation", "travelgo.payments.simulated", "travelgo.stripe.enabled",
            "travelgo.security.csp.enabled", "travelgo.security.auth-abuse.enabled", "travelgo.security.auth-abuse.store",
            "travelgo.security.staff-mfa.enabled", "travelgo.security.staff-mfa.staff-mandatory"})
    void higherPriorityOverridesCannotRelaxReleaseSafeguards(String setting) {
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineFixture", Map.of(setting, "unsafe")));
        assertThrows(IllegalStateException.class, this::validate);
    }
    @ParameterizedTest @ValueSource(strings={"travelgo.security.auth-abuse.hmac-key", "travelgo.security.staff-mfa.encryption-key"})
    void invalidSecurityKeysFailBeforeConnectionsWithoutReflectingSecrets(String setting) {
        environment.setProperty(setting, "private-value");
        Exception failure = assertThrows(IllegalStateException.class, this::validate);
        assertFalse(failure.toString().contains("private-value")); assertNull(failure.getCause());
    }
    @ParameterizedTest @ValueSource(strings={"max-attempts", "cooldown-seconds", "challenge-seconds", "session-seconds", "step-up-seconds"})
    void invalidMfaBoundsFailBeforeBeanCreation(String setting) {
        environment.setProperty("travelgo.security.staff-mfa." + setting, "0");
        assertThrows(IllegalStateException.class, this::validate);
    }
    @ParameterizedTest @ValueSource(strings={"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD", "SPRING_APPLICATION_JSON",
            "TRAVELGO_BOOTSTRAP_ADMIN_PASSWORD", "TRAVELGO_PROVISION_ADMIN_PASSWORD", "travelgo.bootstrap.admin.email", "travelgo.catalogue.install"})
    void conflictingEnvironmentAndAccountOrCatalogueProvisioningAreRejected(String setting) {
        environment.setProperty(setting, "private-value");
        Exception failure = assertThrows(IllegalStateException.class, this::validate); assertFalse(failure.toString().contains("private-value"));
    }
    @ParameterizedTest @ValueSource(strings={"spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.username", "spring.datasource.hikari.password",
            "spring.datasource.hikari.data-source-class-name", "spring.datasource.hikari.data-source-properties.sslMode",
            "spring.datasource.jndi-name", "spring.datasource.type", "SPRING_DATASOURCE_HIKARI_JDBCURL",
            "SPRING_DATASOURCE_HIKARI_JDBC_URL", "SPRING_DATASOURCE_HIKARI_USERNAME", "SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_USER"})
    void poolBindingsCannotReplaceValidatedConnectionsOrCredentials(String setting) {
        environment.setProperty(setting, "private-value");
        Exception failure = assertThrows(IllegalStateException.class, this::validate);
        assertFalse(failure.toString().contains("private-value")); assertNull(failure.getCause());
    }
    @Test void missingRelativePublicAndOverlappingStorageAreRejected() throws Exception {
        String original = environment.getProperty("travelgo.upload-directory");
        for (String unsafe : new String[]{"relative/documents", directory.resolve("missing").toString(),
                Files.createDirectory(directory.resolve("static")).toString(), directory.resolve("logs").toString()}) {
            environment.setProperty("travelgo.upload-directory", unsafe); assertThrows(IllegalStateException.class, this::validate);
        }
        environment.setProperty("travelgo.upload-directory", original); assertDoesNotThrow(this::validate);
    }
    @Test void rotatingAndCustomLogDestinationsCannotBypassPrivateStorageChecks() throws Exception {
        String original = environment.getProperty("logging.logback.rollingpolicy.file-name-pattern");
        for (String unsafe : new String[]{"relative/travelgo.%i.log", directory.resolve("static/travelgo.%i.log").toString(),
                directory.resolve("logs/../logs/travelgo.%i.log").toString()}) {
            environment.setProperty("logging.logback.rollingpolicy.file-name-pattern", unsafe);
            assertThrows(IllegalStateException.class, this::validate);
        }
        environment.setProperty("logging.logback.rollingpolicy.file-name-pattern", original);
        environment.setProperty("logging.file.name", directory.resolve("logs/../logs/travelgo.log").toString());
        assertThrows(IllegalStateException.class, this::validate);
        environment.setProperty("logging.file.name", directory.resolve("logs/travelgo.log").toString());
        environment.setProperty("logging.config", "private-value");
        Exception failure = assertThrows(IllegalStateException.class, this::validate); assertFalse(failure.toString().contains("private-value"));
    }
    @Test void linkedStorageAncestorsCannotPassStartupOrReadiness() throws Exception {
        Path real = Files.createDirectory(directory.resolve("real"));
        Files.createDirectory(real.resolve("documents"));
        Path linked = directory.resolve("linked");
        try { Files.createSymbolicLink(linked, real); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            assumeTrue(false, "Host does not permit real symbolic links; hosted Linux verification must run this case.");
        }
        Path documents = linked.resolve("documents");
        assertFalse(Files.isSymbolicLink(documents));
        assertFalse(ProductionEnvironmentValidator.isAccessiblePrivateDirectory(documents));
        environment.setProperty("travelgo.upload-directory", documents.toString());
        assertThrows(IllegalStateException.class, this::validate);
    }
    @Test void guardIsRegisteredAndRejectsBeforeAnyBeanIsCreated() {
        GuardFixture.initialized.set(false);
        var app = new SpringApplication(GuardFixture.class); app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false); app.setRegisterShutdownHook(false);
        assertThrows(IllegalStateException.class, () -> app.run("--spring.profiles.active=prod", "--spring.config.import=",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:1/travelgo", "--spring.datasource.username=root", "--spring.datasource.password=synthetic"));
        assertFalse(GuardFixture.initialized.get());
    }
    @Test void registeredGuardAllowsAValidProdContextWithoutADataSourceOrServer() {
        GuardFixture.initialized.set(false);
        var arguments = new ArrayList<String>();
        arguments.add("--spring.profiles.active=prod"); arguments.add("--spring.config.import=");
        for (var source : environment.getPropertySources()) if (source instanceof EnumerablePropertySource<?> values)
            for (String name : values.getPropertyNames()) arguments.add("--" + name + "=" + environment.getProperty(name));
        var app = new SpringApplication(GuardFixture.class); app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false); app.setRegisterShutdownHook(false);
        // This configuration-only fixture must not replace the suite's logging
        // system or keep a Windows file appender open after its context closes.
        app.setListeners(app.getListeners().stream()
                .filter(listener -> !listener.getClass().getName().endsWith(".LoggingApplicationListener")).toList());
        try (var context = app.run(arguments.toArray(String[]::new))) {
            assertTrue(GuardFixture.initialized.get());
            assertEquals(0, context.getBeansOfType(javax.sql.DataSource.class).size());
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class GuardFixture {
        static final AtomicBoolean initialized = new AtomicBoolean();
        @Bean Object sentinel() { initialized.set(true); return new Object(); }
    }
}
