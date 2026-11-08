package com.travelgo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Static delivery can be cached; login/session/CSRF responses must remain private. */
@SpringBootTest(properties = {"spring.config.import=", "travelgo.mail.enabled=false",
        "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
class StaticDeliveryIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired com.travelgo.repository.SystemSettingsRepository settings;

    @org.junit.jupiter.api.BeforeEach void syntheticAgencyConfiguration() {
        if (settings.count() == 0) {
            var value = new com.travelgo.entity.SystemSettings();
            value.setCompanyName("Synthetic delivery test agency");
            settings.saveAndFlush(value);
        }
    }

    @Test void staticCachingDoesNotLeakIntoLoginFormsOrAuthenticatedRedirects() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        for (String path : new String[]{"/css/brand.css", "/js/main.js",
                "/css/fonts/inter-latin-300-700-3100e775e861.woff2",
                "/css/fonts/plus-jakarta-sans-latin-400-800-153fc85b7029.woff2"}) {
            var asset = mvc.perform(get(path)).andReturn().getResponse();
            assertEquals(200, asset.getStatus(), path);
            String policy = asset.getHeader("Cache-Control");
            assertNotNull(policy, path);
            assertTrue(policy.contains("max-age=3600"), path + ": " + policy);
            assertFalse(policy.contains("no-store"), path + ": " + policy);
            if (path.endsWith(".woff2")) {
                assertTrue(asset.getContentAsByteArray().length > 10000, "Must serve a font binary rather than a login redirect");
                assertNotNull(asset.getContentType());
                assertFalse(asset.getContentType().startsWith("text/html"), path);
            }
        }
        for (String path : new String[]{"/auth/login", "/customer/dashboard"}) {
            var response = mvc.perform(get(path)).andReturn().getResponse();
            assertTrue(response.getStatus() == 200 || response.getStatus() == 302, path);
            String policy = response.getHeader("Cache-Control");
            assertNotNull(policy, path);
            assertTrue(policy.contains("no-store"), path + ": " + policy);
            assertFalse(policy.contains("max-age=3600"), path + ": " + policy);
        }
    }
}
