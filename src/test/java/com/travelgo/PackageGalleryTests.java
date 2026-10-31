package com.travelgo;

import com.travelgo.dto.PackageRequest;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.TourPackageService;
import com.travelgo.service.SystemSettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** Covers the staff editor, persisted image records and customer detail page on an isolated database. */
@SpringBootTest(properties={"spring.config.import=", "spring.jpa.open-in-view=true"})
@ActiveProfiles("test")
@Import(TestDatabaseSafetyConfiguration.class)
class PackageGalleryTests {
    @Autowired TourPackageService catalogue;
    @Autowired SystemSettingsService settings;
    @Autowired TourPackageRepository packages;
    @Autowired DestinationRepository destinations;
    @Autowired PackageCategoryRepository categories;
    @Autowired RoleRepository roles;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @PersistenceContext EntityManager em;
    TransactionTemplate tx;
    TourPackage tour;

    @BeforeEach void fixture() {
        tx = new TransactionTemplate(transactions);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        User consultant = tx.execute(status -> {
            roles.findByRoleName("ADMIN").orElseGet(() -> {
                Role reference = new Role(); reference.setRoleName("ADMIN"); return roles.save(reference);
            });
            Destination destination = new Destination();
            destination.setCity("Gallery city"); destination.setCountry("Gallery country");
            destinations.save(destination);
            PackageCategory category = new PackageCategory(); category.setName("Gallery " + UUID.randomUUID());
            categories.save(category);
            tour = new TourPackage(); tour.setName("Gallery tour"); tour.setDestination(destination); tour.setCategory(category);
            tour.setBasePrice(new BigDecimal("100.00")); tour.setDurationDays(4); tour.setMaxCapacity(10);
            packages.save(tour);
            Role role = roles.findByRoleName("TRAVEL_CONSULTANT").orElseGet(() -> {
                Role added = new Role(); added.setRoleName("TRAVEL_CONSULTANT"); return roles.save(added);
            });
            return users.save(new User("Gallery consultant", UUID.randomUUID() + "@example.invalid", "unused-test-password", role));
        });
        assertNotNull(settings.getSettings(), "Public catalogue pages require initialized agency settings");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                consultant.getEmail(), "unused", List.of(new SimpleGrantedAuthority("ROLE_TRAVEL_CONSULTANT"))));
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    private void saveGallery(String urls) {
        catalogue.savePackage(tour.getId(), new PackageRequest(tour.getName(), tour.getCategory().getId(),
                tour.getDestination().getId(), "Gallery description", tour.getBasePrice(), tour.getDurationDays(),
                null, "Guided visits", tour.getMaxCapacity(), "/images/paris.jpg", null, null, null, urls));
    }

    private record StoredPhoto(Long id, String url, String caption, int position) {}
    private List<StoredPhoto> storedPhotos() {
        return tx.execute(status -> packages.findById(tour.getId()).orElseThrow().getGallery().stream()
                .map(image -> new StoredPhoto(image.getId(), image.getUrl(), image.getCaption(), image.getDisplayOrder())).toList());
    }

    private String customerPage() throws Exception {
        SecurityContextHolder.clearContext();
        var result = mvc.perform(get("/packages/" + tour.getId())).andReturn();
        assertEquals(200, result.getResponse().getStatus(), () -> failureTypes(result.getResolvedException()));
        return result.getResponse().getContentAsString();
    }

    private String failureTypes(Throwable error) {
        StringBuilder summary = new StringBuilder("Package page failure types:");
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            summary.append(' ').append(error.getClass().getName());
            if (error.getStackTrace().length > 0) summary.append(" at ").append(error.getStackTrace()[0]);
        }
        return summary.toString();
    }

    private String gallerySection(String html) {
        int start = html.indexOf("data-package-gallery");
        assertTrue(start >= 0, "Stored photos must be visible on the customer package page");
        return html.substring(start, html.indexOf("</section>", start));
    }

    @Test void unchangedOrOmittedGalleryPreservesImageIdentityAndCaptions() {
        saveGallery("/images/paris.jpg\n/images/rome.jpg");
        tx.executeWithoutResult(status -> packages.findById(tour.getId()).orElseThrow().getGallery().get(0).setCaption("Agency photo"));
        List<StoredPhoto> before = storedPhotos();
        saveGallery(" /images/paris.jpg \r\n /images/rome.jpg ");
        assertEquals(before, storedPhotos());
        saveGallery(null);
        assertEquals(before, storedPhotos());
    }

    @Test void explicitlyClearingGalleryRemovesStoredPhotosAndCustomerSection() throws Exception {
        saveGallery("/images/paris.jpg\n/images/rome.jpg");
        saveGallery(" \r\n ");
        assertTrue(storedPhotos().isEmpty());
        Long remaining = tx.execute(status -> em.createQuery(
                "select count(i) from PackageImage i where i.tourPackage.id = :id", Long.class)
                .setParameter("id", tour.getId()).getSingleResult());
        assertEquals(0L, remaining);
        assertFalse(customerPage().contains("data-package-gallery"));
    }

    @Test void customerGalleryUsesStoredDisplayOrderAndEscapedAccessibleCaptions() throws Exception {
        saveGallery("/images/paris.jpg\n/images/rome.jpg");
        tx.executeWithoutResult(status -> {
            var gallery = packages.findById(tour.getId()).orElseThrow().getGallery();
            gallery.get(0).setDisplayOrder(5); gallery.get(0).setCaption("Paris <view>");
            gallery.get(1).setDisplayOrder(1); gallery.get(1).setCaption("Rome & gardens");
        });
        String section = gallerySection(customerPage());
        assertTrue(section.indexOf("src=\"/images/rome.jpg\"") < section.indexOf("src=\"/images/paris.jpg\""));
        assertTrue(section.contains("alt=\"Rome &amp; gardens\""));
        assertTrue(section.contains("Paris &lt;view&gt;"));
        assertTrue(section.contains("loading=\"lazy\"") && section.contains("decoding=\"async\""));
        assertTrue(section.contains("paris-4k.jpg") && section.contains("rome-4k.jpg"));
    }

    @Test void unsafeHistoricalImageUsesUnavailablePlaceholderWithoutChangingItsRecord() throws Exception {
        tx.executeWithoutResult(status -> {
            var stored = packages.findById(tour.getId()).orElseThrow();
            PackageImage image = new PackageImage(); image.setTourPackage(stored);
            image.setUrl("javascript:alert(1)"); image.setCaption("Historical caption"); stored.getGallery().add(image);
        });
        String section = gallerySection(customerPage());
        assertTrue(section.contains("src=\"/images/property-photo-unavailable.svg\""));
        assertTrue(section.contains("alt=\"Photo unavailable\""));
        assertFalse(section.contains("javascript:") || section.contains("Historical caption"));
        assertEquals("javascript:alert(1)", storedPhotos().get(0).url());
    }

    @Test void invalidReplacementIsRejectedAndExistingGalleryRemainsIntact() {
        saveGallery("/images/paris.jpg");
        List<StoredPhoto> before = storedPhotos();
        assertThrows(IllegalArgumentException.class, () -> saveGallery("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> saveGallery("https://example.invalid/" + "p".repeat(260)));
        assertEquals(before, storedPhotos());
    }
}
