package com.travelgo;

import com.travelgo.config.ContentSecurityPolicyFilter;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.open-in-view=true", "travelgo.mail.enabled=false",
        "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false",
        "travelgo.security.csp.enabled=true"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
class ContentSecurityPolicyIntegrationTests {
    @Autowired com.travelgo.repository.SystemSettingsRepository settings;
    @org.junit.jupiter.api.BeforeEach void syntheticAgencyConfiguration() {
        if (settings.count() == 0) {
            var value = new com.travelgo.entity.SystemSettings(); value.setCompanyName("Synthetic agency");
            settings.saveAndFlush(value);
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy securityFilters;
    @Autowired FilterRegistrationBean<ContentSecurityPolicyFilter> registration;

    @Test void actualPublicTemplatesUseTheResponseNonceAndChangeItOnTheNextRequest() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String previous = null;
        int scripts = 0;
        for (String url : new String[]{"/", "/packages", "/destinations", "/auth/login", "/auth/register", "/auth/forgot-password"}) {
            var result = mvc.perform(get(url)).andReturn();
            var response = result.getResponse();
            assertEquals(200, response.getStatus(), () -> url + ": " + result.getResolvedException());
            String policy = response.getHeader("Content-Security-Policy");
            var nonceMatcher = Pattern.compile("script-src 'nonce-([^']+)'").matcher(policy);
            assertTrue(nonceMatcher.find(), url);
            String nonce = nonceMatcher.group(1);
            assertNotEquals(previous, nonce);
            var tags = Pattern.compile("<script\\b([^>]*)>").matcher(response.getContentAsString());
            while (tags.find()) {
                assertTrue(tags.group(1).contains("nonce=\"" + nonce + "\""), url + ": " + tags.group());
                scripts++;
            }
            assertFalse(Pattern.compile("(?i)\\son[a-z]+=\"").matcher(response.getContentAsString()).find(), url);
            previous = nonce;
        }
        assertTrue(scripts >= 18);
    }

    @Test void headerExistsOnCsrfDenialsAndLoginRedirectsAndFilterHasOneExecutionPoint() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        assertNotNull(mvc.perform(post("/auth/login")).andExpect(status().isForbidden())
                .andReturn().getResponse().getHeader("Content-Security-Policy"));
        assertNotNull(mvc.perform(get("/customer/bookings")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getHeader("Content-Security-Policy"));
        var filters = securityFilters.getFilterChains().get(0).getFilters();
        assertEquals(1, filters.stream().filter(ContentSecurityPolicyFilter.class::isInstance).count());
        int csp = -1, headers = -1;
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i) instanceof ContentSecurityPolicyFilter) csp = i;
            if (filters.get(i) instanceof HeaderWriterFilter) headers = i;
        }
        assertTrue(csp >= 0 && csp < headers);
        assertFalse(registration.isEnabled());
    }
}
