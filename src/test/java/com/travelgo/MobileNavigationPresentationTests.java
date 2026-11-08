package com.travelgo;

import com.travelgo.entity.Permission;
import com.travelgo.entity.SystemSettings;
import com.travelgo.entity.User;
import com.travelgo.repository.PermissionRepository;
import com.travelgo.repository.SystemSettingsRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real secured templates against inherited guarded H2 fixtures; no browser or business POSTs. */
class MobileNavigationPresentationTests extends WorkflowIntegrationTests {
    private static final List<String> PUBLIC_ROUTES = List.of(
            "/", "/destinations", "/packages", "/visa-info", "/about", "/contact", "/faq");
    private static final List<String> CUSTOMER_ROUTES = List.of(
            "/customer/dashboard", "/customer/bookings", "/customer/visas",
            "/customer/inquiries", "/customer/notifications", "/customer/profile");

    @Autowired SystemSettingsRepository settingsRepository;
    @Autowired PermissionRepository permissionRepository;

    @BeforeEach void navigationIdentityFixture() {
        settingsRepository.findFirstByOrderByIdAsc()
                .orElseGet(() -> settingsRepository.saveAndFlush(new SystemSettings()));
    }

    @Test void mobileNavigationAnonymousHomeKeepsEveryPublicJourneyWithoutPrivateControls() throws Exception {
        Page page = render(null, "/");
        String header = publicHeader(page);
        assertPublicJourneys(header);
        assertEquals(List.of("/auth/login", "/auth/register"), hrefs(header).stream()
                .filter(path -> path.startsWith("/auth/")).toList());
        assertTrue(hrefs(header).stream().noneMatch(MobileNavigationPresentationTests::privateRoute));
        assertFalse(hasClass(header, "details", "account-menu"));
        assertFalse(hasClass(header, "details", "notification-center"));
        assertEquals(0, logoutForms(page.html()).size(), "Anonymous navigation must not render a sign-out form");
    }

    @Test void mobileNavigationEveryPublicDestinationRendersTheSameReachableNavigation() throws Exception {
        for (String route : PUBLIC_ROUTES) {
            Page page = render(null, route);
            assertPublicJourneys(publicHeader(page));
            assertEquals(0, logoutForms(page.html()).size());
        }
    }

    @Test void mobileNavigationCustomerPagesKeepAccountJourneysNotificationsAndCsrfLogout() throws Exception {
        for (String route : List.of("/", "/customer/dashboard")) {
            Page page = render(customer, route);
            String header = publicHeader(page);
            assertPublicJourneys(header);
            assertEquals(CUSTOMER_ROUTES, hrefs(elementByClass(header, "details", "account-menu")));
            assertTrue(hrefs(header).stream().noneMatch(path -> path.startsWith("/admin/") || path.startsWith("/staff/")));
            assertAuthenticatedControls(page, header);
        }
    }

    @Test void mobileNavigationStaffAndAdministratorPublicMenusKeepOnlyTheirRoleJourneys() throws Exception {
        for (User actor : List.of(consultant, officer, admin)) {
            Page page = render(actor, "/");
            String header = publicHeader(page);
            assertPublicJourneys(header);
            String dashboard = actor == admin ? "/admin/dashboard" : "/staff/dashboard";
            assertEquals(List.of(dashboard), hrefs(elementByClass(header, "details", "account-menu")));
            assertAuthenticatedControls(page, header);
        }
    }

    @Test void mobileNavigationWorkspacesKeepRoleBoundariesAndOneCsrfLogout() throws Exception {
        assignStoredNavigationPermissions();
        for (User actor : List.of(consultant, officer, admin)) {
            Page page = render(actor, actor == admin ? "/admin/dashboard" : "/staff/dashboard");
            String sidebar = elementByClass(page.html(), "aside", "admin-sidebar");
            List<String> routes = hrefs(sidebar);
            assertTrue(routes.contains(actor == admin ? "/admin/dashboard" : "/staff/dashboard"));
            assertTrue(routes.contains("/"), "Every workspace must retain its return to the public website");
            assertFalse(routes.stream().anyMatch(path -> path.startsWith("/customer/")));
            assertFalse(routes.contains("/auth/login"));
            assertFalse(routes.contains("/auth/register"));
            if (actor == admin) {
                assertTrue(routes.containsAll(List.of("/admin/users", "/admin/staff", "/admin/settings")));
                assertFalse(routes.stream().anyMatch(path -> path.startsWith("/staff/")));
            } else {
                assertFalse(routes.stream().anyMatch(path -> path.startsWith("/admin/")));
                if (actor == consultant) {
                    assertTrue(routes.containsAll(List.of("/staff/packages", "/staff/destinations", "/staff/bookings")));
                    assertFalse(routes.contains("/staff/visas"));
                    assertFalse(routes.contains("/staff/payments"));
                } else {
                    assertTrue(routes.containsAll(List.of("/staff/visas", "/staff/visas/checklists", "/staff/payments")));
                    assertFalse(routes.contains("/staff/packages"));
                    assertFalse(routes.contains("/staff/bookings"));
                }
            }
            assertSingleCsrfLogout(page);
        }
    }

    @Test void mobileNavigationSavedAgencyIdentityReachesPublicCustomerAndWorkspaceSurfaces() throws Exception {
        assignStoredNavigationPermissions();
        SystemSettings original = settingsRepository.findFirstByOrderByIdAsc().orElseThrow();
        String name = original.getCompanyName();
        String logo = original.getLogoUrl();
        String websiteStatus = original.getWebsiteStatus();
        try {
            original.setCompanyName("Synthetic Navigation Agency");
            original.setLogoUrl("/images/agency/synthetic-navigation-logo.png");
            original.setWebsiteStatus("ACTIVE");
            settingsRepository.saveAndFlush(original);
            List<Page> pages = List.of(render(null, "/"), render(customer, "/customer/dashboard"),
                    render(consultant, "/staff/dashboard"), render(officer, "/staff/dashboard"),
                    render(admin, "/admin/dashboard"));
            for (Page page : pages) {
                String navigation = hasClass(page.html(), "header", "navbar")
                        ? publicHeader(page) : elementByClass(page.html(), "aside", "admin-sidebar");
                assertTrue(navigation.contains("Synthetic Navigation Agency"), "Saved agency name must remain in navigation");
                assertTrue(Pattern.compile("<img\\b[^>]*\\bsrc=\"/images/agency/synthetic-navigation-logo\\.png\"")
                        .matcher(navigation).find(), "Saved custom logo must remain in navigation");
                String image = openingTagByAttribute(navigation, "img", "src", "/images/agency/synthetic-navigation-logo.png");
                assertEquals("", attribute(image, "srcset"), "Official delivery copies must never replace a saved agency logo");
            }
        } finally {
            SystemSettings restored = settingsRepository.findById(original.getId()).orElseThrow();
            restored.setCompanyName(name);
            restored.setLogoUrl(logo);
            restored.setWebsiteStatus(websiteStatus);
            settingsRepository.saveAndFlush(restored);
        }
    }

    @Test void mobileNavigationOfficialIdentityUsesSmallDeliveryCopiesWithoutChangingItsFallback() throws Exception {
        SystemSettings settings = settingsRepository.findFirstByOrderByIdAsc().orElseThrow();
        String previous = settings.getLogoUrl();
        try {
            for (String officialLogo : List.of("/images/travelgo-logo.png", "/images/brand/travelgo-airborne.png")) {
                settings.setLogoUrl(officialLogo);
                settingsRepository.saveAndFlush(settings);
                for (String route : List.of("/", "/auth/login")) {
                    String html = render(null, route).html();
                    var images = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
                    int officialImages = 0;
                    while (images.find()) {
                        String image = images.group();
                        if (!"/images/brand/travelgo-airborne.png".equals(attribute(image, "src"))) continue;
                        officialImages++;
                        assertEquals("/images/brand/travelgo-airborne-192.webp 192w, /images/brand/travelgo-airborne-384.webp 384w",
                                attribute(image, "srcset"));
                        assertFalse(attribute(image, "sizes").isBlank());
                    }
                    assertTrue(officialImages > 0, "The approved identity and PNG fallback remain available");
                    assertTrue(html.contains("href=\"/images/brand/travelgo-favicon-64.png\""));
                }
            }
        } finally {
            settings.setLogoUrl(previous);
            settingsRepository.saveAndFlush(settings);
        }
    }

    private Page render(User actor, String path) throws Exception {
        SecurityContextHolder.clearContext();
        var request = get(path);
        if (actor != null) request.with(staffUser(actor.getEmail(), actor.getRole().getRoleName()));
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new Page(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
                (CsrfToken) result.getRequest().getAttribute("_csrf"));
    }

    private String publicHeader(Page page) {
        return elementByClass(page.html(), "header", "navbar");
    }

    private void assertPublicJourneys(String header) {
        String navigation = elementByAttribute(header, "nav", "id", "primary-navigation");
        assertEquals(PUBLIC_ROUTES, hrefs(navigation), "The responsive menu must retain all seven public routes exactly once");
        String toggle = openingTagByClass(header, "button", "nav-toggle");
        assertEquals("false", attribute(toggle, "aria-expanded"));
        String controlledId = attribute(toggle, "aria-controls");
        assertEquals("site-navigation-panel", controlledId, "The opener controls links and account actions together");
        String panel = openingTagByAttribute(header, "div", "id", controlledId);
        assertTrue(List.of(attribute(panel, "class").split("\\s+")).contains("nav-panel"));
        String close = openingTagByClass(header, "button", "nav-close");
        assertEquals("button", attribute(close, "type"));
        assertFalse(attribute(close, "aria-label").isBlank(), "Drawer dismissal needs an accessible name");
        assertTrue(close.contains("data-nav-dismiss"));
        assertTrue(hasClass(header, "div", "nav-panel-heading"));
        assertTrue(hasClass(header, "button", "nav-backdrop"));
        assertFalse(attribute(toggle, "aria-label").isBlank(), "Navigation opener needs an accessible name");
    }

    private void assertAuthenticatedControls(Page page, String header) {
        assertFalse(hrefs(header).contains("/auth/login"));
        assertFalse(hrefs(header).contains("/auth/register"));
        assertTrue(hasClass(header, "details", "notification-center"), "Signed-in navigation must retain notifications");
        assertTrue(header.contains("data-feed-url=\"/notifications/feed\""));
        assertSingleCsrfLogout(page);
    }

    private void assertSingleCsrfLogout(Page page) {
        List<String> forms = logoutForms(page.html());
        assertEquals(1, forms.size(), "Responsive layouts must keep one native sign-out form");
        String form = forms.get(0);
        assertEquals("post", attribute(form.substring(0, form.indexOf('>') + 1), "method").toLowerCase(java.util.Locale.ROOT));
        assertNotNull(page.csrf(), "Rendering a sign-out form must resolve a CSRF token");
        String input = openingTagByAttribute(form, "input", "name", page.csrf().getParameterName());
        assertEquals("hidden", attribute(input, "type"));
        assertTrue(page.csrf().getToken().equals(attribute(input, "value")), "Sign-out must retain the request's CSRF token");
    }

    private void assignStoredNavigationPermissions() {
        tx.executeWithoutResult(ignored -> {
            for (String roleName : List.of("ADMIN", "TRAVEL_CONSULTANT", "VISA_OFFICER")) {
                var role = roleRepo.findByRoleName(roleName).orElseThrow();
                var assigned = new HashSet<>(role.getPermissions());
                for (var authority : authorities(roleName)) {
                    if (!authority.getAuthority().startsWith("PERM_")) continue;
                    String name = authority.getAuthority().substring("PERM_".length());
                    assigned.add(permissionRepository.findByPermissionName(name).orElseGet(() ->
                            permissionRepository.save(new Permission(name, "Synthetic navigation permission", "TEST"))));
                }
                role.setPermissions(assigned);
                roleRepo.save(role);
            }
        });
    }

    private static boolean privateRoute(String path) {
        return path.startsWith("/admin/") || path.startsWith("/staff/") || path.startsWith("/customer/");
    }

    private static List<String> hrefs(String html) {
        var links = new ArrayList<String>();
        var tags = Pattern.compile("<a\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (tags.find()) links.add(attribute(tags.group(), "href"));
        return links;
    }

    private static List<String> logoutForms(String html) {
        var forms = new ArrayList<String>();
        var matcher = Pattern.compile("<form\\b[^>]*>.*?</form\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (matcher.find()) {
            String form = matcher.group();
            if ("/auth/logout".equals(attribute(form.substring(0, form.indexOf('>') + 1), "action"))) forms.add(form);
        }
        return forms;
    }

    private static boolean hasClass(String html, String tag, String cssClass) {
        var tags = Pattern.compile("<" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (tags.find()) if (List.of(attribute(tags.group(), "class").split("\\s+")).contains(cssClass)) return true;
        return false;
    }

    private static String elementByClass(String html, String tag, String cssClass) {
        return elementStartingAt(html, tag, openingTagByClass(html, tag, cssClass));
    }

    private static String elementByAttribute(String html, String tag, String name, String value) {
        return elementStartingAt(html, tag, openingTagByAttribute(html, tag, name, value));
    }

    private static String elementStartingAt(String html, String tag, String openingTag) {
        int start = html.indexOf(openingTag);
        var close = Pattern.compile("</" + tag + "\\s*>", Pattern.CASE_INSENSITIVE).matcher(html);
        assertTrue(close.find(start + openingTag.length()), "Missing closing " + tag);
        return html.substring(start, close.end());
    }

    private static String openingTagByClass(String html, String tag, String cssClass) {
        return uniqueOpeningTag(html, tag, "class", cssClass, true);
    }

    private static String openingTagByAttribute(String html, String tag, String name, String value) {
        return uniqueOpeningTag(html, tag, name, value, false);
    }

    private static String uniqueOpeningTag(String html, String tag, String name, String value, boolean classToken) {
        var matches = new ArrayList<String>();
        var tags = Pattern.compile("<" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (tags.find()) {
            String actual = attribute(tags.group(), name);
            if (classToken ? List.of(actual.split("\\s+")).contains(value) : value.equals(actual)) matches.add(tags.group());
        }
        assertEquals(1, matches.size(), "Expected one " + tag + " with " + name + "=" + value);
        return matches.get(0);
    }

    private static String attribute(String tag, String name) {
        var matcher = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "\\s*=\\s*([\"'])(.*?)\\1", Pattern.DOTALL).matcher(tag);
        return matcher.find() ? matcher.group(2) : "";
    }

    private record Page(String html, CsrfToken csrf) { }
}
