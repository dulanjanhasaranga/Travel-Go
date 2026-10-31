package com.travelgo;

import com.travelgo.entity.Role;
import com.travelgo.entity.SystemSettings;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.SystemSettingsRepository;
import com.travelgo.service.SystemSettingsService;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fresh synthetic settings initialization through the real proxy, not an outer test transaction. */
@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:travelgo-settings-initialization;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "travelgo.mail.enabled=false", "travelgo.mail.dispatch-enabled=false", "travelgo.mail.account-retry-enabled=false"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class SystemSettingsInitializationTests {
    @Autowired SystemSettingsService service;
    @Autowired SystemSettingsRepository settings;
    @Autowired RoleRepository roles;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void fixture() {
        roles.findByRoleName("ADMIN").orElseGet(() -> roles.save(new Role("ADMIN", "Synthetic initialization lock reference")));
        settings.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void publicMaintenanceCheckInitializesFreshSettingsInsideARealTransaction() throws Exception {
        assertEquals(0, settings.count());
        mvc.perform(get("/")).andExpect(status().isOk());
        assertEquals(1, settings.count());
        assertFalse(service.isMaintenanceMode());
        assertEquals("TravelGO", settings.findFirstByOrderByIdAsc().orElseThrow().getCompanyName());
    }
    @Test void concurrentFirstChecksCreateExactlyOneSingleton() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try {
            Callable<Boolean> check = () -> { ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS)); return service.isMaintenanceMode(); };
            Future<Boolean> first = executor.submit(check), second = executor.submit(check);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            assertFalse(first.get(15, TimeUnit.SECONDS)); assertFalse(second.get(15, TimeUnit.SECONDS));
            assertEquals(1, settings.count());
        } finally { executor.shutdownNow(); }
    }
    @Test void existingAgencyIdentityAndMaintenanceFlagArePreserved() throws Exception {
        var existing = new SystemSettings(); existing.setCompanyName("Synthetic agency identity");
        existing.setWebsiteStatus("MAINTENANCE"); existing.setMaintenanceMessage("Synthetic maintenance message");
        Long id = settings.save(existing).getId();
        assertTrue(service.isMaintenanceMode());
        mvc.perform(get("/")).andExpect(status().is3xxRedirection());
        assertEquals(1, settings.count());
        var current = settings.findFirstByOrderByIdAsc().orElseThrow();
        assertEquals(id, current.getId()); assertEquals("Synthetic agency identity", current.getCompanyName());
        assertEquals("Synthetic maintenance message", current.getMaintenanceMessage());
    }
}
