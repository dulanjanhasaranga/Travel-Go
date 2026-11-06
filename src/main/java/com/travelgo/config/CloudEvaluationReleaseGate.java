package com.travelgo.config;

import com.travelgo.service.BrevoTransactionalMailSender;
import com.travelgo.service.SupabaseVisaDocumentStorage;
import com.travelgo.service.VisaDocumentScanner;
import com.travelgo.util.SafeMySqlUrl;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/**
 * Validates the deliberately separate Render/Aiven/Supabase academic-demo release before
 * Spring can initialise a datasource, accounts, files, or a third-party provider. This is
 * intentionally not the production profile: it permits only fictional demonstration data.
 */
public final class CloudEvaluationReleaseGate implements EnvironmentPostProcessor, Ordered {
    private static final String PROFILE = "cloud-evaluation";
    private static final String ACCEPTANCE = "SYNTHETIC_DEMO_ONLY";
    private static final String TRUSTSTORE = "file:/tmp/travelgo-aiven/truststore.p12";
    private static final Set<String> TRUST_PROPERTIES = Set.of(
            "springdatasourcehikaridatasourcepropertiestrustcertificatekeystoreurl",
            "springdatasourcehikaridatasourcepropertiestrustcertificatekeystoretype",
            "springdatasourcehikaridatasourcepropertiestrustcertificatekeystorepassword",
            "springdatasourcehikaridatasourcepropertiesfallbacktosystemtruststore");

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains(PROFILE)) return;
        try {
            validate(environment);
        } catch (InvalidRelease invalid) {
            throw invalid;
        } catch (RuntimeException unresolved) {
            // URI, conversion, placeholder, and provider parsing errors can contain a secret.
            throw new InvalidRelease("Hosted demonstration configuration could not be validated. Check the documented release variables.");
        }
    }

    private void validate(ConfigurableEnvironment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length != 1 || !PROFILE.equals(profiles[0]))
            fail("Hosted demonstration must use only the cloud-evaluation profile.");
        equal(environment, "travelgo.cloud-evaluation.release-acceptance", ACCEPTANCE);
        rejectDatasourceOverrides(environment);

        database(environment);
        publicOrigin(required(environment, "travelgo.public-base-url"));
        equal(environment, "spring.jpa.hibernate.ddl-auto", "validate");
        equal(environment, "spring.jpa.mapping-resources", "META-INF/cloud-evaluation-orm.xml");
        equal(environment, "spring.sql.init.mode", "never");
        equal(environment, "travelgo.data-initializer.enabled", "false");
        equal(environment, "travelgo.demo.enabled", "false");
        equal(environment, "server.address", "0.0.0.0");
        equal(environment, "server.port", "8081");
        equal(environment, "server.forward-headers-strategy", "native");
        equal(environment, "server.servlet.session.cookie.secure", "true");
        equal(environment, "server.servlet.session.cookie.http-only", "true");
        equal(environment, "server.error.include-message", "never");
        equal(environment, "server.error.include-stacktrace", "never");
        if (!environment.getProperty("logging.file.name", "").isBlank()
                || !environment.getProperty("logging.logback.rollingpolicy.file-name-pattern", "").isBlank()
                || !environment.getProperty("logging.config", "").isBlank())
            fail("Hosted demonstration logs must go only to the managed host log stream.");

        security(environment);
        providers(environment);
        documents(environment);
        for (String setting : new String[]{"travelgo.catalogue.install", "travelgo.catalogue.restore-original"})
            if (!"false".equalsIgnoreCase(environment.getProperty(setting, "false")))
                fail("Hosted demonstration catalogue installation must remain disabled.");
        for (String setting : new String[]{"travelgo.bootstrap.admin.email", "travelgo.bootstrap.admin.password",
                "travelgo.bootstrap.admin.name", "TRAVELGO_BOOTSTRAP_ADMIN_EMAIL", "TRAVELGO_BOOTSTRAP_ADMIN_PASSWORD",
                "TRAVELGO_BOOTSTRAP_ADMIN_NAME", "TRAVELGO_PROVISION_ACTOR_EMAIL", "TRAVELGO_PROVISION_ACTOR_PASSWORD",
                "TRAVELGO_PROVISION_ADMIN_NAME", "TRAVELGO_PROVISION_ADMIN_EMAIL", "TRAVELGO_PROVISION_ADMIN_PASSWORD"})
            if (!environment.getProperty(setting, "").isBlank())
                fail("Routine hosted demonstration startup cannot provision an administrator.");
    }

    private void rejectDatasourceOverrides(ConfigurableEnvironment environment) {
        for (String override : new String[]{"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD", "SPRING_APPLICATION_JSON"})
            if (!environment.getProperty(override, "").isBlank())
                fail("Remove conflicting Spring datasource/JSON environment overrides before hosted startup.");
        for (var source : environment.getPropertySources()) if (source instanceof EnumerablePropertySource<?> values)
            for (String name : values.getPropertyNames()) {
                String canonical = name.toLowerCase(Locale.ROOT).replaceAll("[._-]", "");
                if (canonical.matches("springdatasource(?:type|jndiname)|springdatasourcehikari(?:jdbcurl|username|password|datasourceclassname|datasourcejndi)"))
                    fail("Remove alternate datasource/pool connection settings; use the reviewed cloud datasource settings.");
                if (canonical.startsWith("springdatasourcehikaridatasourceproperties") && !TRUST_PROPERTIES.contains(canonical))
                    fail("Only the reviewed per-driver Aiven trust properties may be configured for the datasource.");
            }
    }

    private void database(ConfigurableEnvironment environment) {
        String username = required(environment, "spring.datasource.username");
        if (!"travelgo_demo_app".equals(username) || unsafeSecret(required(environment, "spring.datasource.password")))
            fail("Hosted demonstration requires its restricted travelgo_demo_app database account and a private password.");
        String url = required(environment, "spring.datasource.url");
        SafeMySqlUrl.validate(url);
        try {
            URI uri = URI.create(url.substring("jdbc:".length()));
            if (!uri.getHost().toLowerCase(Locale.ROOT).endsWith(".aivencloud.com") || uri.getPort() < 1)
                fail("Hosted demonstration requires its dedicated Aiven MySQL endpoint.");
            Map<String, String> parameters = parameters(uri.getRawQuery());
            if (!"VERIFY_IDENTITY".equalsIgnoreCase(parameters.get("sslmode"))
                    || !"UTC".equalsIgnoreCase(parameters.get("servertimezone"))
                    || parameters.size() != 2)
                fail("Hosted demonstration database URL must use only identity-verified TLS and UTC.");
        } catch (InvalidRelease invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            fail("Hosted demonstration database URL is invalid.");
        }
        equal(environment, "spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl", TRUSTSTORE);
        equal(environment, "spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreType", "PKCS12");
        equal(environment, "spring.datasource.hikari.data-source-properties.fallbackToSystemTrustStore", "false");
        String storePassword = required(environment, "spring.datasource.hikari.data-source-properties.trustCertificateKeyStorePassword");
        if (storePassword.length() < 16 || storePassword.length() > 256 || unsafeSecret(storePassword))
            fail("Hosted demonstration requires a private Aiven truststore password.");
    }

    private Map<String, String> parameters(String query) {
        if (query == null || query.isBlank()) fail("Hosted demonstration database URL requires reviewed TLS parameters.");
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : query.split("&", -1)) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (key.isBlank() || result.putIfAbsent(key, value) != null) fail("Hosted demonstration database URL is invalid.");
        }
        return result;
    }

    private void security(ConfigurableEnvironment environment) {
        equal(environment, "travelgo.security.csp.enabled", "true");
        equal(environment, "travelgo.security.auth-abuse.enabled", "true");
        equal(environment, "travelgo.security.auth-abuse.store", "jdbc");
        SharedAuthenticationRequestLimiter.validateKey(required(environment, "travelgo.security.auth-abuse.hmac-key"));
        equal(environment, "travelgo.security.staff-mfa.enabled", "true");
        equal(environment, "travelgo.security.staff-mfa.staff-mandatory", "true");
        StaffMfaProperties mfa = new StaffMfaProperties();
        mfa.setEnabled(true);
        mfa.setEncryptionKey(required(environment, "travelgo.security.staff-mfa.encryption-key"));
        mfa.setMaxAttempts(environment.getProperty("travelgo.security.staff-mfa.max-attempts", Integer.class, 5));
        mfa.setCooldownSeconds(environment.getProperty("travelgo.security.staff-mfa.cooldown-seconds", Integer.class, 300));
        mfa.setChallengeSeconds(environment.getProperty("travelgo.security.staff-mfa.challenge-seconds", Integer.class, 600));
        mfa.setSessionSeconds(environment.getProperty("travelgo.security.staff-mfa.session-seconds", Integer.class, 28800));
        mfa.setStepUpSeconds(environment.getProperty("travelgo.security.staff-mfa.step-up-seconds", Integer.class, 300));
        mfa.validate();
    }

    private void providers(ConfigurableEnvironment environment) {
        equal(environment, "travelgo.mail.enabled", "true");
        equal(environment, "travelgo.mail.dispatch-enabled", "true");
        equal(environment, "travelgo.mail.transport", "brevo-api");
        String from = required(environment, "travelgo.mail.from");
        if (!from.matches("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$") || from.endsWith(".example"))
            fail("Hosted demonstration requires an approved transactional sender address.");
        if (!BrevoTransactionalMailSender.isConfigurationValid(
                required(environment, "travelgo.mail.brevo-api-key"), required(environment, "travelgo.public-base-url")))
            fail("Hosted demonstration requires a private Brevo HTTPS API key and an HTTPS website origin.");
        equal(environment, "travelgo.mail.feedback.brevo-enabled", "false");
        equal(environment, "travelgo.payments.simulated", "true");
        equal(environment, "travelgo.stripe.enabled", "false");
        equal(environment, "travelgo.payhere.enabled", "false");
        equal(environment, "travelgo.auth.google.enabled", "false");
        equal(environment, "travelgo.bookings.require-published-policy", "true");
        equal(environment, "travelgo.bookings.require-supplier-confirmation", "true");
    }

    private void documents(ConfigurableEnvironment environment) {
        equal(environment, "travelgo.documents.storage-backend", "supabase");
        equal(environment, "travelgo.upload-directory", "/tmp/travelgo-document-fallback");
        equal(environment, "travelgo.documents.max-file-bytes", "3000000");
        equal(environment, "travelgo.documents.max-image-pixels", "8000000");
        equal(environment, "spring.servlet.multipart.max-file-size", "3000000B");
        equal(environment, "spring.servlet.multipart.max-request-size", "3500000B");
        try {
            SupabaseVisaDocumentStorage.validateConfiguration(
                    required(environment, "travelgo.documents.supabase.project-url"),
                    required(environment, "travelgo.documents.supabase.bucket"),
                    required(environment, "travelgo.documents.supabase.service-key"),
                    environment.getProperty("travelgo.documents.supabase.connect-timeout-ms", Integer.class, 3000),
                    environment.getProperty("travelgo.documents.supabase.request-timeout-ms", Integer.class, 15000));
        } catch (RuntimeException invalid) {
            fail("Hosted demonstration private document storage settings are invalid.");
        }
        equal(environment, "travelgo.documents.scan.enabled", "true");
        equal(environment, "travelgo.documents.scan.backend", "cloudmersive");
        equal(environment, "travelgo.documents.scan.max-bytes", "3000000");
        VisaDocumentScanner.validateEnvironment(environment);
    }

    private void publicOrigin(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || loopback(host)
                || host.toLowerCase(Locale.ROOT).matches(".*\\.(invalid|example|test)$")
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || (uri.getPort() != -1 && uri.getPort() != 443))
            fail("Hosted demonstration requires the real HTTPS website origin without credentials, paths, queries or fragments.");
    }

    private String required(ConfigurableEnvironment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank() || value.startsWith("${"))
            fail("Missing required hosted demonstration setting: " + name + ".");
        return value;
    }

    private void equal(ConfigurableEnvironment environment, String name, String expected) {
        if (!expected.equalsIgnoreCase(required(environment, name))) fail("Unsafe hosted demonstration setting: " + name + ".");
    }

    private boolean unsafeSecret(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("placeholder") || normalized.contains("replace") || normalized.contains("changeme") || value.startsWith("${");
    }

    private boolean loopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }
    private void fail(String message) { throw new InvalidRelease(message); }
    private static final class InvalidRelease extends IllegalStateException {
        InvalidRelease(String message) { super(message); }
    }
}
