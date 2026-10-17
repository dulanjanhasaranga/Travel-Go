package com.travelgo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** No Spring refresh, sockets, filesystem writes, JPA or external services. */
class ReferenceDesignBrowserPreviewTests {
    private final Map<String, String> required = ReferenceDesignBrowserPreview.settings(
            Path.of("").toAbsolutePath().normalize(), "synthetic-unit-only");

    private GenericApplicationContext context(Map<String, Object> overrides, String... profiles) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        Map<String, Object> properties = new LinkedHashMap<>(required);
        properties.putAll(overrides);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-preview", properties));
        return context;
    }

    @Test void fixedFreshH2ConfigurationPassesWithoutInitializingTheContext() {
        try (GenericApplicationContext context = context(Map.of(), "test")) {
            assertDoesNotThrow(() -> new ReferenceDesignBrowserPreview.IsolationGuard(required).initialize(context));
            assertEquals("8081", required.get("server.port"));
            assertEquals("false", required.get("travelgo.mail.enabled"));
            assertEquals("false", required.get("travelgo.mail.account-retry-enabled"));
        }
    }

    @Test void workingMysqlEndpointIsRefusedBeforeJpa() {
        reject(Map.of("spring.datasource.url", "jdbc:mysql://localhost:3306/travelgo"));
    }

    @Test void alternatePoolConnectionsAndGenericJpaSettingsAreRefused() {
        for (String key : new String[]{"spring.datasource.hikari.jdbc-url", "SPRING_DATASOURCE_HIKARI_JDBC_URL",
                "spring.datasource.hikari.connection-init-sql", "spring.datasource.hikari.connection-test-query",
                "spring.datasource.jndi-name", "spring.mail.jndi-name", "spring.jpa.properties.hibernate.connection.url"}) {
            reject(Map.of(key, "synthetic-unreviewed-value"));
        }
    }

    @Test void extraProfilesAreRefused() {
        try (GenericApplicationContext context = context(Map.of(), "test", "mysql")) {
            assertThrows(IllegalStateException.class, () -> new ReferenceDesignBrowserPreview.IsolationGuard(required).initialize(context));
        }
    }

    @Test void mailProvidersAndReservedPortCannotBeEnabled() {
        for (String key : new String[]{"travelgo.mail.enabled", "travelgo.mail.dispatch-enabled", "travelgo.mail.account-retry-enabled",
                "spring.mail.test-connection", "travelgo.payhere.enabled", "travelgo.stripe.enabled", "travelgo.auth.google.enabled"}) {
            reject(Map.of(key, "true"));
        }
        reject(Map.of("server.port", "8080"));
    }

    @Test void directConfigurationArgumentsAreRefusedBeforeTheNativeSocketProbe() {
        assertThrows(IllegalArgumentException.class, () -> ReferenceDesignBrowserPreview.main(new String[]{"--server.port=8080"}));
    }

    private void reject(Map<String, Object> overrides) {
        try (GenericApplicationContext context = context(overrides, "test")) {
            assertThrows(IllegalStateException.class, () -> new ReferenceDesignBrowserPreview.IsolationGuard(required).initialize(context));
        }
    }
}
