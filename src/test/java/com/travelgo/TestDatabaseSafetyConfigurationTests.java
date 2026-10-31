package com.travelgo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/** Configuration-only checks: no DataSource, connection, JPA or application is created. */
class TestDatabaseSafetyConfigurationTests {
    private static void guard(MockEnvironment environment) {
        TestDatabaseSafetyConfiguration.isolatedDatabaseGuard(environment)
                .postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    @Test void separateContextsReceiveDifferentMemoryNamesAndRetainEveryUrlOption() {
        String original = "jdbc:h2:mem:travelgo-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=MySQL";
        var first = new MockEnvironment().withProperty("spring.datasource.url", original);
        var second = new MockEnvironment().withProperty("spring.datasource.url", original);
        guard(first); guard(second);
        String firstUrl = first.getProperty("spring.datasource.url");
        String secondUrl = second.getProperty("spring.datasource.url");
        assertNotEquals(original, firstUrl);
        assertNotEquals(firstUrl, secondUrl);
        assertTrue(firstUrl.startsWith("jdbc:h2:mem:travelgo-test-context-"));
        assertTrue(secondUrl.startsWith("jdbc:h2:mem:travelgo-test-context-"));
        assertEquals(original.substring(original.indexOf(';')), firstUrl.substring(firstUrl.indexOf(';')));
        assertEquals(original.substring(original.indexOf(';')), secondUrl.substring(secondUrl.indexOf(';')));
        var withoutOptions = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:h2:mem:no-options");
        guard(withoutOptions);
        assertTrue(withoutOptions.getProperty("spring.datasource.url").startsWith("jdbc:h2:mem:no-options-context-"));
    }

    @Test void onlyTheExplicitDisposableNativeMysqlUrlIsRetainedByteForByte() {
        for (String url : new String[]{"jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test",
                "jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test?useSSL=false&allowPublicKeyRetrieval=true"}) {
            var environment = new MockEnvironment().withProperty("spring.datasource.url", url);
            guard(environment);
            assertEquals(url, environment.getProperty("spring.datasource.url"));
            assertFalse(environment.getPropertySources().contains("travelgo-isolated-test-database"));
        }
    }

    @Test void workingPersistentOrUnknownTargetsAreRejectedBeforeAnyUrlRewrite() {
        for (String url : new String[]{"", "jdbc:mysql://localhost:3306/travelgo",
                "jdbc:mysql://127.0.0.1:33317/travelgo", "jdbc:mysql://127.0.0.1:33318/travelgo_disposable_test",
                "jdbc:h2:file:./working", "jdbc:h2:tcp://127.0.0.1/mem:working", "jdbc:postgresql://localhost/working"}) {
            var environment = new MockEnvironment().withProperty("spring.datasource.url", url);
            assertThrows(IllegalStateException.class, () -> guard(environment));
            assertEquals(url, environment.getProperty("spring.datasource.url"));
            assertFalse(environment.getPropertySources().contains("travelgo-isolated-test-database"));
        }
    }
}
