package com.travelgo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/** Explicit H2 visual preview only. Never include test classes in a release JAR. */
public final class ReferenceDesignBrowserPreview {
    private ReferenceDesignBrowserPreview() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new IllegalArgumentException("The isolated visual preview accepts no configuration overrides.");
        requireUnusedPreviewPort();
        Path project = Path.of("").toAbsolutePath().normalize();
        if (!Files.isRegularFile(project.resolve("pom.xml"))
                || !Files.isDirectory(project.resolve("src/main/resources/templates"))
                || !Files.isDirectory(project.resolve("src/main/resources/static"))) {
            throw new IllegalStateException("Run the isolated preview from the TravelGO Java project directory.");
        }
        String runKey = UUID.randomUUID().toString();
        Map<String, String> settings = settings(project, runKey);
        System.setProperty("spring.devtools.restart.enabled", "false");
        SpringApplication application = new SpringApplication(TravelGoApplication.class, PlatformBrowserPreview.Fixtures.class);
        application.addInitializers(new IsolationGuard(settings));
        application.run(settings.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
    }

    static Map<String, String> settings(Path project, String runKey) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("spring.profiles.active", "test");
        values.put("spring.config.import", "");
        values.put("spring.config.additional-location", "");
        values.put("spring.config.location", "classpath:/application.properties,classpath:/application-test.properties");
        values.put("logging.config", "");
        values.put("server.address", "127.0.0.1");
        values.put("server.port", "8081");
        values.put("server.forward-headers-strategy", "none");
        values.put("server.servlet.session.cookie.name", "TRAVELGO_REFERENCE_PREVIEW");
        values.put("spring.datasource.url", "jdbc:h2:mem:travelgo-reference-design-" + runKey + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        values.put("spring.datasource.driver-class-name", "org.h2.Driver");
        values.put("spring.datasource.username", "sa");
        values.put("spring.datasource.password", "");
        values.put("spring.jpa.database-platform", "org.hibernate.dialect.H2Dialect");
        values.put("spring.jpa.hibernate.ddl-auto", "create-drop");
        values.put("spring.jpa.open-in-view", "true");
        values.put("spring.jpa.show-sql", "false");
        values.put("spring.sql.init.mode", "never");
        values.put("spring.flyway.enabled", "false");
        values.put("spring.liquibase.enabled", "false");
        values.put("spring.h2.console.enabled", "false");
        values.put("travelgo.data-initializer.enabled", "false");
        values.put("travelgo.demo.enabled", "false");
        values.put("travelgo.catalogue.install", "false");
        values.put("travelgo.catalogue.restore-original", "false");
        values.put("travelgo.bootstrap.admin.email", "");
        values.put("travelgo.bootstrap.admin.password", "");
        values.put("travelgo.bootstrap.admin.name", "");
        values.put("travelgo.mail.enabled", "false");
        values.put("travelgo.mail.dispatch-enabled", "false");
        values.put("travelgo.mail.account-retry-enabled", "false");
        values.put("travelgo.mail.feedback.brevo-enabled", "false");
        values.put("spring.mail.test-connection", "false");
        values.put("spring.mail.host", "127.0.0.1");
        values.put("spring.mail.port", "25252");
        values.put("spring.mail.username", "");
        values.put("spring.mail.password", "");
        values.put("spring.mail.properties.mail.debug", "false");
        values.put("spring.mail.properties.mail.debug.auth", "false");
        values.put("travelgo.mail.from", "preview@travelgo.invalid");
        values.put("travelgo.public-base-url", "http://127.0.0.1:8081");
        values.put("travelgo.payments.simulated", "true");
        values.put("travelgo.payhere.enabled", "false");
        values.put("travelgo.stripe.enabled", "false");
        values.put("travelgo.auth.google.enabled", "false");
        values.put("travelgo.bookings.require-published-policy", "true");
        values.put("travelgo.bookings.require-supplier-confirmation", "true");
        values.put("travelgo.upload-directory", project.resolve("target/reference-design-preview-" + runKey + "/uploads").toString());
        values.put("spring.thymeleaf.prefix", project.resolve("src/main/resources/templates").toUri().toString());
        values.put("spring.thymeleaf.cache", "false");
        values.put("spring.web.resources.static-locations", project.resolve("src/main/resources/static").toUri().toString());
        values.put("spring.web.resources.cache.period", "0");
        values.put("spring.web.resources.chain.cache", "false");
        return values;
    }

    static void requireUnusedPreviewPort() throws IOException {
        // Refuse before Spring configuration/JPA. Tomcat also refuses if another
        // process wins the port between this probe and its own socket bind.
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8081));
        } catch (IOException occupied) {
            throw new IOException("Port 8081 is unavailable. Identify its owner; this preview never stops or replaces another process.");
        }
    }

    static final class IsolationGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        private final Map<String, String> required;

        IsolationGuard(Map<String, String> required) { this.required = Map.copyOf(required); }

        @Override public void initialize(ConfigurableApplicationContext context) {
            ConfigurableEnvironment environment = context.getEnvironment();
            if (!Arrays.equals(environment.getActiveProfiles(), new String[]{"test"})) refuse();
            for (Map.Entry<String, String> entry : required.entrySet()) {
                if (!entry.getValue().equals(environment.getProperty(entry.getKey()))) refuse();
            }
            // Alternate pool, JNDI or Hibernate connection properties can bypass
            // spring.datasource.url. Refuse them before a DataSource is created.
            for (var source : environment.getPropertySources()) {
                if (!(source instanceof EnumerablePropertySource<?> values)) continue;
                for (String name : values.getPropertyNames()) {
                    String canonical = name.toLowerCase(Locale.ROOT).replaceAll("[._-]", "");
                    if (canonical.matches("springdatasource(?:type|jndiname)|springmailjndiname")
                            || canonical.startsWith("springdatasourcehikari")
                            || canonical.startsWith("springjpaproperties")) refuse();
                }
            }
        }

        private static void refuse() {
            throw new IllegalStateException("Isolated visual preview configuration changed. Refusing startup before database initialization; no private configuration values are displayed.");
        }
    }
}
