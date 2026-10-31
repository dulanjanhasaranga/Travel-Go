package com.travelgo;

import com.travelgo.entity.SystemSettings;
import com.travelgo.repository.SystemSettingsRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Read-only catalogue/export and ambiguous persisted currency checks on the guarded MySQL fixture. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.ActiveProfiles(value = "mysql-test", inheritProfiles = false)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test?useSSL=false&allowPublicKeyRetrieval=true",
        "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false",
        "travelgo.payhere.enabled=false", "travelgo.stripe.enabled=false", "travelgo.auth.google.enabled=false",
        "travelgo.demo.enabled=false", "travelgo.bootstrap.admin.email=", "travelgo.bootstrap.admin.password="})
class MySqlContentReadinessIT extends ContentReadinessMvcTests {
    @Autowired SystemSettingsRepository settings;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;

    @org.junit.jupiter.api.BeforeEach
    void requireNativeMySql(@Autowired javax.sql.DataSource source) throws Exception {
        try (var connection = source.getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:33317/travelgo_disposable_test?"));
            assertEquals("travelgo_disposable_test", connection.getCatalog());
        }
    }

    @Test void persistedCurrencyAmbiguityIsReportedWithoutConvertingPricesOrRepairingSettings() throws Exception {
        assertTrue(settings.findAll().isEmpty(), "The synthetic fixture must start without agency settings.");
        SystemSettings first = new SystemSettings(); first.setCurrency("LKR"); settings.saveAndFlush(first);
        Long firstId = first.getId(), tourId = tour.getId();
        BigDecimal recordedPrice = tour.getBasePrice(); long auditCount = audit.count();

        mvc.perform(get("/admin/content-review").with(user(admin.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Recorded currency setting: LKR")))
                .andExpect(content().string(containsString("no amounts have been converted")));
        mvc.perform(get("/admin/content-review/workflow.md").with(user(admin.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Review required; no amounts have been converted")));
        entityManager.flush(); entityManager.clear();
        assertEquals("LKR", settings.findById(firstId).orElseThrow().getCurrency());
        assertEquals(recordedPrice, packages.findById(tourId).orElseThrow().getBasePrice());
        assertEquals(1, settings.count()); assertEquals(auditCount, audit.count());

        SystemSettings second = new SystemSettings(); second.setCurrency("USD"); settings.saveAndFlush(second);
        Long secondId = second.getId();
        mvc.perform(get("/admin/content-review").with(user(admin.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Multiple settings records")));
        entityManager.flush(); entityManager.clear();
        assertEquals(2, settings.count());
        assertEquals("LKR", settings.findById(firstId).orElseThrow().getCurrency());
        assertEquals("USD", settings.findById(secondId).orElseThrow().getCurrency());
        assertEquals(recordedPrice, packages.findById(tourId).orElseThrow().getBasePrice());
        assertEquals(auditCount, audit.count());
    }
}
