package com.travelgo.config;

import com.travelgo.util.SafeMySqlUrl;
import com.travelgo.util.PasswordPolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/**
 * One-time fresh-schema gate for the hosted academic demo. It performs a bounded metadata
 * read before Hibernate can create tables and permits only an entirely empty Aiven schema.
 */
public final class CloudEvaluationSchemaInitializationGate implements EnvironmentPostProcessor, Ordered {
    static final String PROFILE = "cloud-evaluation-init";
    private static final String ACCEPTANCE = "SYNTHETIC_DEMO_ONLY";
    private static final String ACTION = "CREATE_EMPTY_SCHEMA";
    private static final String CONFIRMATION = "INITIALIZE_EMPTY_AIVEN_SCHEMA";
    private static final String TRUSTSTORE = "file:/tmp/travelgo-aiven/truststore.p12";
    private final SchemaInspector inspector;

    public CloudEvaluationSchemaInitializationGate() { this(CloudEvaluationSchemaInitializationGate::requireEmptySchema); }
    CloudEvaluationSchemaInitializationGate(SchemaInspector inspector) { this.inspector = inspector; }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains(PROFILE)) return;
        try {
            if (application == null || !application.getAllSources().contains(CloudEvaluationSchemaApplication.class))
                fail("Hosted schema initialization requires its dedicated initialization application.");
            validate(environment);
        } catch (InvalidRelease invalid) {
            throw invalid;
        } catch (RuntimeException unresolved) {
            throw new InvalidRelease("Hosted demonstration schema initialization could not be validated. Check the documented initialization variables.");
        }
    }

    private void validate(ConfigurableEnvironment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length != 1 || !PROFILE.equals(profiles[0])) fail("Hosted schema initialization must use only the cloud-evaluation-init profile.");
        equal(environment, "travelgo.cloud-evaluation.release-acceptance", ACCEPTANCE);
        equal(environment, "travelgo.cloud-evaluation.schema-init", ACTION);
        equal(environment, "travelgo.cloud-evaluation.init-confirmation", CONFIRMATION);
        equal(environment, "spring.jpa.hibernate.ddl-auto", "create");
        equal(environment, "spring.sql.init.mode", "never");
        equal(environment, "travelgo.data-initializer.enabled", "true");
        equal(environment, "travelgo.demo.enabled", "false");
        equal(environment, "travelgo.mail.enabled", "false");
        equal(environment, "travelgo.mail.dispatch-enabled", "false");
        equal(environment, "travelgo.payments.simulated", "true");
        equal(environment, "travelgo.stripe.enabled", "false");
        equal(environment, "travelgo.payhere.enabled", "false");
        equal(environment, "travelgo.auth.google.enabled", "false");
        equal(environment, "travelgo.catalogue.install", "false");
        equal(environment, "travelgo.catalogue.restore-original", "false");
        validateAdministrator(environment);
        rejectOverrides(environment);
        DatabaseSettings database = database(environment);
        try {
            inspector.requireEmpty(database);
        } catch (InvalidRelease invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            throw new InvalidRelease("Hosted demonstration schema must be an empty dedicated Aiven database before initialization.");
        }
    }

    private void validateAdministrator(ConfigurableEnvironment environment) {
        String email = environment.getProperty("travelgo.bootstrap.admin.email", "").trim();
        String password = environment.getProperty("travelgo.bootstrap.admin.password", "");
        String name = environment.getProperty("travelgo.bootstrap.admin.name", "").trim();
        if (email.length() > 150 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            fail("Hosted schema initialization requires a valid initial administrator email.");
        if (!PasswordPolicy.isValid(password))
            fail("Hosted schema initialization requires an initial administrator password of 12 to 72 UTF-8 bytes.");
        if (name.length() > 100)
            fail("Hosted schema initialization administrator name must be 100 characters or fewer.");
    }

    private void rejectOverrides(ConfigurableEnvironment environment) {
        for (String override : new String[]{"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD", "SPRING_APPLICATION_JSON"})
            if (!environment.getProperty(override, "").isBlank())
                fail("Remove conflicting Spring datasource/JSON environment overrides before schema initialization.");
        for (var source : environment.getPropertySources()) if (source instanceof EnumerablePropertySource<?> values)
            for (String name : values.getPropertyNames()) {
                String canonical = name.toLowerCase(Locale.ROOT).replaceAll("[._-]", "");
                if (canonical.matches("springdatasource(?:type|jndiname)|springdatasourcehikari(?:jdbcurl|username|password|datasourceclassname|datasourcejndi)"))
                    fail("Remove alternate datasource/pool connection settings before schema initialization.");
                if (canonical.startsWith("springjpahibernate") && !canonical.equals("springjpahibernateddlauto")
                        || canonical.startsWith("springjpapropertieshibernatehbm2ddl")
                        || canonical.startsWith("springjpapropertiesjakartapersistenceschemageneration")
                        || canonical.startsWith("springjpapropertiesjavaxpersistenceschemageneration"))
                    fail("Schema initialization must use only its reviewed Hibernate create setting.");
            }
    }

    private DatabaseSettings database(ConfigurableEnvironment environment) {
        String url = required(environment, "spring.datasource.url");
        String username = required(environment, "spring.datasource.username");
        String password = required(environment, "spring.datasource.password");
        SafeMySqlUrl.validate(url);
        try {
            URI uri = URI.create(url.substring("jdbc:".length()));
            if (uri.getHost() == null || !uri.getHost().toLowerCase(Locale.ROOT).endsWith(".aivencloud.com")
                    || uri.getPort() < 1 || !"/defaultdb".equals(uri.getPath()))
                fail("Hosted schema initialization requires the dedicated Aiven defaultdb endpoint.");
            Map<String, String> parameters = parameters(uri.getRawQuery());
            if (!"VERIFY_IDENTITY".equalsIgnoreCase(parameters.get("sslmode"))
                    || !"UTC".equalsIgnoreCase(parameters.get("servertimezone")) || parameters.size() != 2)
                fail("Hosted schema initialization database URL must use only identity-verified TLS and UTC.");
        } catch (InvalidRelease invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            fail("Hosted schema initialization database URL is invalid.");
        }
        equal(environment, "spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl", TRUSTSTORE);
        equal(environment, "spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreType", "PKCS12");
        equal(environment, "spring.datasource.hikari.data-source-properties.fallbackToSystemTrustStore", "false");
        String truststorePassword = required(environment, "spring.datasource.hikari.data-source-properties.trustCertificateKeyStorePassword");
        if (username.length() > 150 || username.chars().anyMatch(Character::isWhitespace)
                || unsafeSecret(username) || unsafeSecret(password) || truststorePassword.length() < 16
                || truststorePassword.length() > 256 || unsafeSecret(truststorePassword))
            fail("Hosted schema initialization requires private Aiven credentials and truststore password.");
        return new DatabaseSettings(url, username, password, truststorePassword);
    }

    private Map<String, String> parameters(String query) {
        if (query == null || query.isBlank()) fail("Hosted schema initialization database URL requires reviewed TLS parameters.");
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : query.split("&", -1)) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (key.isBlank() || result.putIfAbsent(key, value) != null) fail("Hosted schema initialization database URL is invalid.");
        }
        return result;
    }

    private static void requireEmptySchema(DatabaseSettings settings) {
        Properties properties = new Properties();
        properties.setProperty("user", settings.username());
        properties.setProperty("password", settings.password());
        properties.setProperty("trustCertificateKeyStoreUrl", TRUSTSTORE);
        properties.setProperty("trustCertificateKeyStoreType", "PKCS12");
        properties.setProperty("trustCertificateKeyStorePassword", settings.truststorePassword());
        properties.setProperty("fallbackToSystemTrustStore", "false");
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "10000");
        try (Connection connection = DriverManager.getConnection(settings.url(), properties);
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) != 0) throw new InvalidRelease("Hosted demonstration schema must be empty; do not rerun its initializer.");
            }
        } catch (InvalidRelease invalid) {
            throw invalid;
        } catch (SQLException unavailable) {
            throw new InvalidRelease("Hosted demonstration schema could not be inspected. Check the Aiven connection and certificate settings.");
        }
    }

    private String required(ConfigurableEnvironment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank() || value.startsWith("${")) fail("Missing required schema initialization setting: " + name + ".");
        return value;
    }
    private void equal(ConfigurableEnvironment environment, String name, String expected) {
        if (!expected.equalsIgnoreCase(required(environment, name))) fail("Unsafe schema initialization setting: " + name + ".");
    }
    private boolean unsafeSecret(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("placeholder") || normalized.contains("replace") || normalized.contains("changeme") || value.startsWith("${");
    }
    private void fail(String message) { throw new InvalidRelease(message); }
    @FunctionalInterface interface SchemaInspector { void requireEmpty(DatabaseSettings settings); }
    record DatabaseSettings(String url, String username, String password, String truststorePassword) { }
    private static final class InvalidRelease extends IllegalStateException { InvalidRelease(String message) { super(message); } }
}
