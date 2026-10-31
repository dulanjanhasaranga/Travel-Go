package com.travelgo;

import com.travelgo.config.AuthenticationRequestLimiter;
import com.travelgo.config.SharedAuthenticationRequestLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the selected production store implementation through the actual secured login filter. */
@SpringBootTest(properties = {"spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:jdbc-auth-http;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false",
        "travelgo.security.auth-abuse.enabled=true", "travelgo.security.auth-abuse.store=jdbc",
        "travelgo.security.auth-abuse.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "travelgo.security.auth-abuse.login-ip-limit=4", "travelgo.security.auth-abuse.login-account-limit=2"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
class JdbcAuthenticationAbuseIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired AuthenticationRequestLimiter limiter;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach void fixture() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM authentication_rate_cells");
        jdbc.update("INSERT INTO authentication_rate_cells(opaque_key,admitted_count,expires_at_ms) VALUES(?,0,0)",
                SharedAuthenticationRequestLimiter.GUARD);
    }

    @Test void configuredJdbcBeanProtectsActualLoginWithoutConsumingInvalidCsrfRequests() throws Exception {
        assertInstanceOf(SharedAuthenticationRequestLimiter.class, limiter);
        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/auth/login").with(request -> { request.setRemoteAddr("192.0.2.80"); return request; })
                    .param("email", "synthetic-jdbc@example.invalid").param("password", "Synthetic-wrong-123!"))
                    .andExpect(status().isForbidden());
        }
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM authentication_rate_cells", Long.class));
        for (String email : new String[]{"synthetic-jdbc@example.invalid", "SYNTHETIC-JDBC@example.invalid"}) {
            mvc.perform(post("/auth/login").with(csrf())
                    .with(request -> { request.setRemoteAddr("192.0.2.80"); return request; })
                    .param("email", email).param("password", "Synthetic-wrong-123!"))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/login?error=true"));
        }
        mvc.perform(post("/auth/login").with(csrf())
                .with(request -> { request.setRemoteAddr("192.0.2.80"); return request; })
                .param("email", "synthetic-jdbc@example.invalid").param("password", "Synthetic-wrong-123!"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM authentication_rate_cells", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM authentication_rate_cells WHERE admitted_count=2", Long.class));
    }

    @Test void missingSharedGuardDeniesHttpLoginWithoutSilentlyUsingMemory() throws Exception {
        assertInstanceOf(SharedAuthenticationRequestLimiter.class, limiter);
        jdbc.update("DELETE FROM authentication_rate_cells");
        mvc.perform(post("/auth/login").with(csrf())
                .with(request -> { request.setRemoteAddr("192.0.2.81"); return request; })
                .param("email", "synthetic-missing-store@example.invalid").param("password", "Synthetic-wrong-123!"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM authentication_rate_cells", Long.class));
    }
}
