package com.travelgo;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import java.util.UUID;

/** Runs before JPA can create/drop any tables. */
@TestConfiguration
public class TestDatabaseSafetyConfiguration {
    @Bean
    static BeanFactoryPostProcessor isolatedDatabaseGuard(ConfigurableEnvironment environment) {
        return factory -> {
            String url = environment.getProperty("spring.datasource.url", "");
            boolean inMemory = url.startsWith("jdbc:h2:mem:");
            boolean disposableMysql = url.matches("jdbc:mysql://127\\.0\\.0\\.1:33317/travelgo_disposable_test(?:\\?.*)?");
            if (!inMemory && !disposableMysql) {
                throw new IllegalStateException("Tests require an in-memory H2 database or the disposable MySQL database on port 33317. Application databases are forbidden.");
            }
            if (inMemory) {
                // Distinct cached Spring contexts must never share a create-drop database:
                // closing/evicting one context would otherwise drop another's active tables.
                // Validate the original target first; never disguise a working URL as H2.
                int options = url.indexOf(';');
                String database = options < 0 ? url : url.substring(0, options);
                String retainedOptions = options < 0 ? "" : url.substring(options);
                String uniqueUrl = database + "-context-" + UUID.randomUUID() + retainedOptions;
                environment.getPropertySources().addFirst(new MapPropertySource(
                        "travelgo-isolated-test-database", Map.of("spring.datasource.url", uniqueUrl)));
            }
        };
    }
}
