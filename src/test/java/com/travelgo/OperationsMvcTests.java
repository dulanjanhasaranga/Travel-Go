package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.OperationsStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
@Transactional
class OperationsMvcTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired SystemSettingsRepository settings;
    @MockitoBean OperationsStatusService operations;
    MockMvc mvc; User admin,customer; Role adminRole;

    @BeforeEach void fixture() {
        adminRole=roles.findByRoleName("ADMIN").orElseGet(()->roles.save(new Role("ADMIN","fixture")));
        var permission=permissions.findByPermissionName("SYSTEM_SETTINGS_VIEW").orElseGet(()->permissions.save(new Permission("SYSTEM_SETTINGS_VIEW","fixture","SYSTEM")));
        adminRole.setPermissions(new java.util.HashSet<>(Set.of(permission))); roles.saveAndFlush(adminRole);
        admin=users.saveAndFlush(new User("Admin","ops-admin-"+UUID.randomUUID()+"@example.invalid","fixture-only-hash",adminRole));
        var customerRole=roles.findByRoleName("CUSTOMER").orElseGet(()->roles.save(new Role("CUSTOMER","fixture")));
        customer=users.saveAndFlush(new User("Customer","ops-customer-"+UUID.randomUUID()+"@example.invalid","fixture-only-hash",customerRole));
        if (settings.count()==0) { var configuration=new SystemSettings(); configuration.setWebsiteStatus("ACTIVE"); settings.saveAndFlush(configuration); }
        when(operations.dependencies()).thenReturn(new OperationsStatusService.DependencyStatus(true,true));
        when(operations.snapshot()).thenReturn(new OperationsStatusService.Snapshot(Instant.parse("2030-01-01T10:00:00Z"),new OperationsStatusService.DependencyStatus(true,true),1,3,1,1073741824L,false,false,true,false,false));
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @Test void anonymousHealthResponseContainsOnlyAggregateStatusAndIsNotCached() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"UP\"}"))
                .andExpect(header().string("Cache-Control","no-store"));
        verify(operations,never()).snapshot();
    }
    @Test void unavailableDependencyReturns503WithoutDiagnostics() throws Exception {
        when(operations.dependencies()).thenReturn(new OperationsStatusService.DependencyStatus(false,true));
        mvc.perform(get("/healthz")).andExpect(status().isServiceUnavailable()).andExpect(content().json("{\"status\":\"DOWN\"}"));
    }
    @Test void maintenanceDoesNotHideHealthFromTheMonitor() throws Exception {
        var configuration=settings.findAll().get(0); configuration.setWebsiteStatus("MAINTENANCE"); configuration.setMaintenanceMessage("Fixture maintenance"); settings.saveAndFlush(configuration);
        mvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"UP\"}"));
    }
    @Test void operationsPageRequiresAdministratorAndModulePermission() throws Exception {
        mvc.perform(get("/admin/operations")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/login"));
        mvc.perform(get("/admin/operations").with(user(customer.getEmail()).roles("CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/operations").with(user(admin.getEmail()).roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/operations").with(user(admin.getEmail()).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"),new SimpleGrantedAuthority("PERM_SYSTEM_SETTINGS_VIEW"))))
                .andExpect(status().isOk()).andExpect(view().name("admin/operations"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Service checks")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Simulated payments")));
    }
    @Test void staleGrantedAuthorityCannotBypassFreshStoredPermissions() throws Exception {
        adminRole.setPermissions(new java.util.HashSet<>()); roles.saveAndFlush(adminRole);
        mvc.perform(get("/admin/operations").with(user(admin.getEmail()).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"),new SimpleGrantedAuthority("PERM_SYSTEM_SETTINGS_VIEW")))).andExpect(status().isForbidden());
        verify(operations,never()).snapshot();
    }
}
