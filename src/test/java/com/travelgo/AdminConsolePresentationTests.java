package com.travelgo;

import com.travelgo.entity.ContactMessage;
import com.travelgo.entity.Permission;
import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.ContactMessageRepository;
import com.travelgo.repository.PermissionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual template rendering against the inherited, guarded in-memory H2 fixtures. */
class AdminConsolePresentationTests extends WorkflowIntegrationTests {
    private static final Path RENDER_ROOT = Path.of("target/admin-console-render-20261031");
    @Autowired PermissionRepository permissionRepository;
    @Autowired ContactMessageRepository inquiryRepository;

    @BeforeEach void assignStoredPermissionsForPresentation() {
        tx.executeWithoutResult(ignored -> {
            for (String roleName : List.of("ADMIN", "TRAVEL_CONSULTANT", "VISA_OFFICER")) {
                Role stored = roleRepo.findByRoleName(roleName).orElseThrow();
                Set<Permission> assigned = new HashSet<>(stored.getPermissions());
                for (var authority : authorities(roleName)) {
                    if (!authority.getAuthority().startsWith("PERM_")) continue;
                    String name = authority.getAuthority().substring("PERM_".length());
                    assigned.add(permissionRepository.findByPermissionName(name).orElseGet(() ->
                            permissionRepository.save(new Permission(name, "Synthetic presentation permission", "ADMIN"))));
                }
                stored.setPermissions(assigned);
                roleRepo.save(stored);
            }
        });
    }

    private String render(User actor, String path) throws Exception {
        SecurityContextHolder.clearContext();
        return mvc.perform(get(path).with(staffUser(actor.getEmail(), actor.getRole().getRoleName())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertConsole(String html) {
        assertTrue(Pattern.compile("<body\\b[^>]*class=\"[^\"]*\\badmin-console\\b[^\"]*\"")
                .matcher(html).find(), "Administrator pages must explicitly opt into the console scope");
        assertTrue(html.contains("/css/admin-console.css"));
        assertTrue(html.contains("/js/admin-console.js"));
        assertTrue(html.contains("data-console-nav-search"));
        assertTrue(html.contains("console-nav-group"));
    }

    private void assertOutsideConsole(String html) {
        assertFalse(Pattern.compile("<body\\b[^>]*class=\"[^\"]*\\badmin-console\\b[^\"]*\"")
                .matcher(html).find(), "Customer and staff pages must retain their existing presentation");
        assertFalse(html.contains("/css/admin-console.css"));
        assertFalse(html.contains("/js/admin-console.js"));
    }

    private void export(String name, String html) throws Exception {
        if (!Boolean.getBoolean("travelgo.adminConsole.export")) return;
        Files.createDirectories(RENDER_ROOT);
        Files.writeString(RENDER_ROOT.resolve(name + ".html"), html, StandardCharsets.UTF_8);
    }

    @Test void allAdministratorPanelsRenderWithOneDedicatedConsoleScope() throws Exception {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("dashboard", "/admin/dashboard");
        pages.put("users", "/admin/users");
        pages.put("user-detail", "/admin/users/" + customer.getId());
        pages.put("staff", "/admin/staff");
        pages.put("staff-new", "/admin/staff/new");
        pages.put("staff-edit", "/admin/staff/" + officer.getId() + "/edit");
        pages.put("roles", "/admin/roles");
        pages.put("role-detail", "/admin/roles/" + consultant.getRole().getId());
        pages.put("permissions", "/admin/permissions");
        pages.put("approvals", "/admin/approvals");
        pages.put("audit-logs", "/admin/audit-logs");
        pages.put("settings", "/admin/settings");
        pages.put("operations", "/admin/operations");
        pages.put("content-review", "/admin/content-review");
        pages.put("email-delivery", "/admin/email-delivery");
        pages.put("reconciliation", "/admin/reconciliation");
        pages.put("travel-policies", "/admin/travel-policies");
        for (var page : pages.entrySet()) {
            String html = render(admin, page.getValue());
            assertConsole(html);
            export(page.getKey(), html);
        }
        assertEquals(17, pages.size());
    }

    @Test void sharedInquiryTemplatesUseTheConsoleOnlyForAdministratorRoutes() throws Exception {
        ContactMessage inquiry = new ContactMessage();
        inquiry.setUser(customer);
        inquiry.setSenderName("Synthetic inquiry customer");
        inquiry.setSenderEmail(customer.getEmail());
        inquiry.setSubject("Synthetic travel inquiry");
        inquiry.setMessage("Please advise on this synthetic travel plan.");
        inquiry = inquiryRepository.saveAndFlush(inquiry);
        for (String suffix : List.of("", "/" + inquiry.getId())) {
            String adminHtml = render(admin, "/admin/inquiries" + suffix);
            assertConsole(adminHtml);
            export(suffix.isEmpty() ? "inquiries" : "inquiry-detail", adminHtml);
            String staffHtml = render(consultant, "/staff/inquiries" + suffix);
            assertOutsideConsole(staffHtml);
            export(suffix.isEmpty() ? "unchanged-staff-inquiries" : "unchanged-staff-inquiry-detail", staffHtml);
        }
    }

    @Test void publicCustomerAndOtherStaffPagesDoNotLoadTheAdministratorAssets() throws Exception {
        var booking = create(false);
        var pages = List.of(
                Map.entry(customer, "/"), Map.entry(customer, "/packages"),
                Map.entry(customer, "/customer/dashboard"), Map.entry(customer, "/customer/bookings"),
                Map.entry(customer, "/customer/bookings/" + booking.getId()),
                Map.entry(customer, "/customer/visas"),
                Map.entry(consultant, "/staff/dashboard"), Map.entry(consultant, "/staff/bookings"),
                Map.entry(consultant, "/staff/packages"), Map.entry(officer, "/staff/visas"));
        for (var page : pages) {
            String html = render(page.getKey(), page.getValue());
            assertOutsideConsole(html);
            String exportName = "unchanged" + page.getValue().replace('/', '-');
            if (page.getValue().startsWith("/customer/bookings/")) exportName = "unchanged-customer-booking-detail";
            export(exportName, html);
        }
    }

    @Test void profileRoleAndSettingsFormsKeepTheirExistingCsrfProtectedActions() throws Exception {
        assertProtectedForm(render(admin, "/admin/users/" + customer.getId()),
                "/admin/users/" + customer.getId() + "/update");
        assertProtectedForm(render(admin, "/admin/staff/new"), "/admin/staff/create");
        assertProtectedForm(render(admin, "/admin/staff/" + officer.getId() + "/edit"),
                "/admin/staff/" + officer.getId() + "/update");
        assertProtectedForm(render(admin, "/admin/roles/" + consultant.getRole().getId()),
                "/admin/roles/" + consultant.getRole().getId() + "/permissions");
        String settings = render(admin, "/admin/settings");
        for (String section : List.of("company", "contact", "hours", "status")) {
            assertProtectedForm(settings, "/admin/settings/" + section);
        }
    }

    private void assertProtectedForm(String html, String action) {
        var form = Pattern.compile("<form\\b[^>]*action=\"" + Pattern.quote(action)
                + "\"[^>]*>(.*?)</form>", Pattern.DOTALL).matcher(html);
        assertTrue(form.find(), "Existing action must remain present: " + action);
        assertTrue(form.group().contains("method=\"post\""));
        assertTrue(form.group().contains("name=\"_csrf\""), "POST form must retain CSRF: " + action);
    }

    @Test void limitedAdministratorsCannotSeeNavigationForUnassignedPermissions() throws Exception {
        tx.executeWithoutResult(ignored -> {
            Role stored = roleRepo.findByRoleName("ADMIN").orElseThrow();
            Permission dashboard = permissionRepository.findByPermissionName("DASHBOARD_VIEW").orElseThrow();
            stored.setPermissions(new HashSet<>(Set.of(dashboard)));
            roleRepo.saveAndFlush(stored);
        });
        SecurityContextHolder.clearContext();
        String html = mvc.perform(get("/admin/dashboard").with(user(admin.getEmail()).authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("PERM_DASHBOARD_VIEW"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var sidebar = Pattern.compile("<aside\\b.*?</aside>", Pattern.DOTALL).matcher(html);
        assertTrue(sidebar.find());
        String navigation = sidebar.group();
        for (String path : List.of("/admin/users", "/admin/staff", "/admin/roles", "/admin/permissions",
                "/admin/settings", "/admin/operations", "/admin/content-review", "/admin/reconciliation")) {
            assertFalse(navigation.contains("href=\"" + path + "\""), "Restricted navigation must remain hidden: " + path);
        }
        assertTrue(navigation.contains("href=\"/admin/dashboard\""));
    }
}
