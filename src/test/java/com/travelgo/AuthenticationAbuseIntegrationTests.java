package com.travelgo;

import com.travelgo.config.AuthenticationAbuseFilter;
import com.travelgo.config.AuthenticationAbuseProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The safety guard runs before JPA: only disposable in-memory H2 is permitted for this suite. */
@SpringBootTest(properties = {"spring.config.import=", "travelgo.mail.enabled=false",
        "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false",
        "travelgo.security.auth-abuse.enabled=true"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
class AuthenticationAbuseIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy securityFilters;
    @Autowired AuthenticationAbuseProperties properties;
    @Autowired FilterRegistrationBean<AuthenticationAbuseFilter> registration;
    private MockMvc mvc;

    @BeforeEach void setUp() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void filterRunsOnceAfterCsrfAndBeforePasswordAuthentication() {
        List<jakarta.servlet.Filter> filters = securityFilters.getFilterChains().get(0).getFilters();
        int csrfIndex = index(filters, CsrfFilter.class);
        int abuseIndex = index(filters, AuthenticationAbuseFilter.class);
        int passwordIndex = index(filters, UsernamePasswordAuthenticationFilter.class);
        assertTrue(csrfIndex >= 0 && abuseIndex > csrfIndex && passwordIndex > abuseIndex);
        assertEquals(1, filters.stream().filter(AuthenticationAbuseFilter.class::isInstance).count());
        assertFalse(registration.isEnabled(), "Do not register a duplicate servlet filter outside SecurityFilterChain");
        assertTrue(properties.enabled());
        assertEquals(20_000, properties.maximumBuckets());
    }

    @Test void invalidCsrfDoesNotExhaustTheBudgetButValidRepeatedLoginIsThrottled() throws Exception {
        for (int i = 0; i < 15; i++) {
            mvc.perform(post("/auth/login").with(request -> { request.setRemoteAddr("192.0.2.50"); return request; })
                    .param("email", "synthetic-unregistered@example.test").param("password", "Synthetic-wrong-123!"))
                    .andExpect(status().isForbidden());
        }
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/auth/login").with(csrf()).with(request -> { request.setRemoteAddr("192.0.2.50"); return request; })
                    .param("email", "synthetic-unregistered@example.test").param("password", "Synthetic-wrong-123!"))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/login?error=true"));
        }
        mvc.perform(post("/auth/login").with(csrf()).with(request -> { request.setRemoteAddr("192.0.2.50"); return request; })
                .param("email", "synthetic-unregistered@example.test").param("password", "Synthetic-wrong-123!"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private static int index(List<jakarta.servlet.Filter> filters, Class<?> type) {
        for (int i = 0; i < filters.size(); i++) if (type.isInstance(filters.get(i))) return i;
        return -1;
    }
}
