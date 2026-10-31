package com.travelgo.config;

import java.net.URI;
import com.travelgo.util.SafeMySqlUrl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/** Validates effective prod configuration before beans, database access or account seeding. */
public final class ProductionEnvironmentValidator implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("prod")) return;
        try {
            validate(environment);
        } catch (InvalidRelease invalid) {
            throw invalid;
        } catch (RuntimeException unresolved) {
            // Placeholder, URI, path and conversion errors can contain secret values.
            throw new InvalidRelease("Production configuration could not be validated. Check the documented release variables.");
        }
    }

    private void validate(ConfigurableEnvironment environment) {
        if (Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.equals("test") || p.equals("mysql-test") || p.equals("demo")))
            fail("Production cannot be combined with test or demo profiles.");
        for (String override : new String[]{"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD", "SPRING_APPLICATION_JSON"})
            if (!environment.getProperty(override, "").isBlank()) fail("Remove conflicting Spring datasource/JSON environment overrides before production startup.");
        // Pool binding can replace DataSourceProperties after this early check.
        // Include relaxed environment spellings and nested driver properties.
        for (var source : environment.getPropertySources()) if (source instanceof EnumerablePropertySource<?> values)
            for (String name : values.getPropertyNames()) {
                String canonical = name.toLowerCase(Locale.ROOT).replaceAll("[._-]", "");
                if (canonical.matches("springdatasource(?:type|jndiname)|springdatasourcehikari(?:jdbcurl|username|password|datasourceclassname|datasourcejndi)")
                        || canonical.startsWith("springdatasourcehikaridatasourceproperties"))
                    fail("Remove alternate datasource/pool connection settings; use the validated application datasource settings.");
            }

        database(environment);
        publicOrigin(required(environment, "travelgo.public-base-url"));
        equal(environment, "spring.jpa.hibernate.ddl-auto", "validate");
        equal(environment, "spring.sql.init.mode", "never");
        equal(environment, "travelgo.data-initializer.enabled", "false");
        equal(environment, "server.servlet.session.cookie.secure", "true");
        equal(environment, "server.servlet.session.cookie.http-only", "true");
        equal(environment, "server.error.include-message", "never");
        equal(environment, "server.error.include-stacktrace", "never");
        equal(environment, "travelgo.demo.enabled", "false");
        equal(environment, "travelgo.bookings.require-published-policy", "true");
        equal(environment, "travelgo.bookings.require-supplier-confirmation", "true");
        equal(environment, "travelgo.payments.simulated", "true");
        equal(environment, "travelgo.stripe.enabled", "false");
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
        equal(environment, "travelgo.documents.scan.enabled", "true");
        // The optional cloud storage adapter has a separate hosted-evaluation acceptance path.
        // Existing production recovery/provisioning remains bound to its private filesystem.
        if (!"filesystem".equals(environment.getProperty("travelgo.documents.storage-backend", "filesystem")))
            fail("Production requires its reviewed private filesystem storage backend.");
        com.travelgo.service.VisaDocumentScanner.validateBackend(
                environment.getProperty("travelgo.documents.scan.backend", "clamd"), true);
        com.travelgo.service.VisaDocumentScanner.validateConfiguration(
                environment.getProperty("travelgo.documents.scan.host", "127.0.0.1"),
                environment.getProperty("travelgo.documents.scan.port", Integer.class, 3310),
                environment.getProperty("travelgo.documents.scan.connect-timeout-ms", Integer.class, 2000),
                environment.getProperty("travelgo.documents.scan.read-timeout-ms", Integer.class, 15000),
                environment.getProperty("travelgo.documents.scan.max-bytes", Long.class, 10485760L));
        for (String disabled : new String[]{"travelgo.catalogue.install", "travelgo.catalogue.restore-original"})
            if (!"false".equalsIgnoreCase(environment.getProperty(disabled, "false"))) fail("Production catalogue installation must remain disabled.");
        for (String bootstrap : new String[]{"travelgo.bootstrap.admin.email", "travelgo.bootstrap.admin.password",
                "TRAVELGO_BOOTSTRAP_ADMIN_EMAIL", "TRAVELGO_BOOTSTRAP_ADMIN_PASSWORD", "TRAVELGO_PROVISION_ACTOR_EMAIL",
                "TRAVELGO_PROVISION_ACTOR_PASSWORD", "TRAVELGO_PROVISION_ADMIN_NAME", "TRAVELGO_PROVISION_ADMIN_EMAIL", "TRAVELGO_PROVISION_ADMIN_PASSWORD"})
            if (!environment.getProperty(bootstrap, "").isBlank()) fail("Remove administrator provisioning credentials from the routine production environment.");

        Path documents = privateDirectory(required(environment, "travelgo.upload-directory"));
        Path logFile = Path.of(required(environment, "logging.file.name"));
        if (!logFile.isAbsolute() || !logFile.equals(logFile.normalize()) || logFile.getParent() == null || Files.isSymbolicLink(logFile))
            fail("Production logging must use an absolute private file path without links.");
        Path logs = privateDirectory(logFile.getParent().toString());
        if (documents.startsWith(logs) || logs.startsWith(documents)) fail("Private document and log directories must be separate.");
        Path rolling = Path.of(required(environment, "logging.logback.rollingpolicy.file-name-pattern"));
        if (!rolling.isAbsolute() || !rolling.equals(rolling.normalize()) || !logs.equals(rolling.getParent()) || Files.isSymbolicLink(rolling))
            fail("Rotating production logs must remain in the same validated private log directory.");
        if (!environment.getProperty("logging.config", "").isBlank()) fail("Custom logging configuration requires a separate deployment review.");
    }

    private void database(ConfigurableEnvironment environment) {
        String username = required(environment, "spring.datasource.username");
        if ("root".equalsIgnoreCase(username.trim())) fail("Production requires a restricted database account.");
        String password = required(environment, "spring.datasource.password");
        if (password.equals("REPLACE_IN_SECRET_MANAGER")) fail("Replace example database credentials in the host secret manager.");
        SafeMySqlUrl.validate(required(environment, "spring.datasource.url"));
    }

    private void publicOrigin(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || loopback(host)
                || host.toLowerCase(Locale.ROOT).matches(".*\\.(invalid|example|test)$")
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)))
            fail("Production requires the real HTTPS website origin without credentials, paths, queries or fragments.");
    }

    private Path privateDirectory(String value) {
        Path directory = Path.of(value);
        if (!directory.isAbsolute()) fail("Production storage directories must be absolute, existing and private.");
        directory = directory.normalize();
        if (!isAccessiblePrivateDirectory(directory)) fail("Production storage must be readable and writable without linked ancestors.");
        for (Path part : directory) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.equals("target") || name.equals("static") || name.equals("public") || name.equals("node_modules"))
                fail("Production storage must be outside public resources and build output.");
        }
        if (directory.getParent() == null) fail("Use a dedicated private storage directory rather than a filesystem root.");
        return directory;
    }

    /** Reused by readiness so a linked storage ancestor cannot be reported as healthy. */
    public static boolean isAccessiblePrivateDirectory(Path directory) {
        try {
            if (!Files.isDirectory(directory) || !Files.isReadable(directory) || !Files.isWritable(directory)) return false;
            for (Path ancestor = directory; ancestor != null; ancestor = ancestor.getParent())
                if (Files.isSymbolicLink(ancestor) || !ancestor.toRealPath().equals(ancestor.toAbsolutePath().normalize())) return false;
            return true;
        } catch (Exception unavailable) { return false; }
    }

    private String required(ConfigurableEnvironment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) fail("Missing required production setting: " + name + ".");
        return value;
    }
    private void equal(ConfigurableEnvironment environment, String name, String expected) {
        if (!expected.equalsIgnoreCase(required(environment, name))) fail("Unsafe production setting: " + name + ".");
    }
    private boolean loopback(String host) { return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host); }
    private void fail(String message) { throw new InvalidRelease(message); }
    private static final class InvalidRelease extends IllegalStateException {
        InvalidRelease(String message) { super(message); }
    }
}
