package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.config.import=", "spring.datasource.url=jdbc:h2:mem:content-review-mvc;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
@Transactional
class ContentReadinessMvcTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PermissionRepository permissions;
    @Autowired TourPackageRepository packages;
    @Autowired HotelRepository hotels;
    @Autowired DestinationRepository destinations;
    @Autowired PackageCategoryRepository categories;
    @Autowired AuditLogRepository audit;
    MockMvc mvc;
    User admin;
    TourPackage tour;

    @BeforeEach void fixture() {
        Role role = roles.findByRoleName("ADMIN").orElseGet(() -> roles.save(new Role("ADMIN", "Synthetic administrator")));
        var permission = permissions.findByPermissionName("SYSTEM_SETTINGS_VIEW").orElseGet(() -> permissions.save(new Permission("SYSTEM_SETTINGS_VIEW", "Synthetic view permission", "ADMIN")));
        role.setPermissions(new HashSet<>(java.util.Set.of(permission))); roles.saveAndFlush(role);
        admin = users.saveAndFlush(new User("Synthetic reviewer", "content-review-" + UUID.randomUUID() + "@example.invalid", "unused-password", role));
        Destination destination = new Destination(); destination.setCity("Synthetic Tokyo"); destination.setCountry("Japan"); destinations.save(destination);
        PackageCategory category = new PackageCategory(); category.setName("Synthetic review category " + UUID.randomUUID()); categories.save(category);
        tour = new TourPackage(); tour.setName("<script>alert('catalogue')</script>"); tour.setDestination(destination); tour.setCategory(category);
        tour.setBasePrice(new BigDecimal("99.00")); tour.setDurationDays(3); tour.setMaxCapacity(5); tour.setImage("/images/tokyo.jpg"); packages.saveAndFlush(tour);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void approvedRoleCanReviewCurrentDatabaseContentWithoutMutatingCatalogue() throws Exception {
        long toursBefore = packages.count(), hotelsBefore = hotels.count(), auditBefore = audit.count();
        mvc.perform(get("/admin/content-review").with(user(admin.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))))
                .andExpect(status().isOk()).andExpect(view().name("admin/content-review"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("Download catalogue worksheet")))
                .andExpect(content().string(containsString("Day-by-day itinerary")))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert('catalogue')</script>"))));
        assertEquals(toursBefore, packages.count()); assertEquals(hotelsBefore, hotels.count()); assertEquals(auditBefore, audit.count());
        assertEquals("<script>alert('catalogue')</script>", packages.findById(tour.getId()).orElseThrow().getName());
    }

    @Test void worksheetDownloadsAreAttachmentsAndDoNotClaimApproval() throws Exception {
        mvc.perform(get("/admin/content-review/catalogue.csv").with(user(admin.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"travelgo-catalogue-review.csv\""))
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(content().string(containsString("Pending agency review")))
                .andExpect(content().string(containsString("Photo owner and license or supplier permission reference")));
        mvc.perform(get("/admin/content-review/workflow.md").with(user(admin.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment; filename=\"travelgo-workflow-acceptance.md\""))
                .andExpect(content().string(containsString("awaiting client review and signature")))
                .andExpect(content().string(containsString("three days before departure")));
    }

    @Test void customersAndBothStaffRolesCannotReadContentReviewOrDownloads() throws Exception {
        for (String role : java.util.List.of("CUSTOMER", "TRAVEL_CONSULTANT", "VISA_OFFICER")) {
            Role storedRole = roles.findByRoleName(role).orElseGet(() -> roles.saveAndFlush(new Role(role, "Synthetic role")));
            User account = users.saveAndFlush(new User("Synthetic " + role, "content-denied-" + UUID.randomUUID()
                    + "@example.invalid", "unused-password", storedRole));
            for (String path : java.util.List.of("/admin/content-review", "/admin/content-review/catalogue.csv", "/admin/content-review/workflow.md")) {
                mvc.perform(get(path).with(user(account.getEmail()).roles(role))).andExpect(status().isForbidden());
            }
        }
    }

    @Test void storedAdministratorPermissionIsRequiredEvenWhenTheRequestClaimsIt() throws Exception {
        admin.getRole().setPermissions(new HashSet<>()); roles.saveAndFlush(admin.getRole());
        mvc.perform(get("/admin/content-review/catalogue.csv").with(user(admin.getEmail()).authorities(WorkflowIntegrationTests.authorities("ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test void anonymousDownloadsRequireSignIn() throws Exception {
        mvc.perform(get("/admin/content-review/catalogue.csv"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/login"));
    }
}
