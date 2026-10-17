package com.travelgo;

import com.travelgo.entity.Departure;
import com.travelgo.entity.Destination;
import com.travelgo.entity.PackageCategory;
import com.travelgo.entity.TourPackage;
import com.travelgo.repository.DepartureRepository;
import com.travelgo.repository.DestinationRepository;
import com.travelgo.repository.PackageCategoryRepository;
import com.travelgo.repository.TourPackageRepository;
import com.travelgo.service.CatalogueDiscoveryService;
import com.travelgo.service.DestinationService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/** Regression checks use a separately named, guarded H2 database and rollback every test. */
@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.open-in-view=false",
        "spring.datasource.url=jdbc:h2:mem:travelgo-catalogue-pages;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("test")
@Import({TestDatabaseSafetyConfiguration.class, CataloguePagingFreshnessTests.Time.class})
@Transactional
class CataloguePagingFreshnessTests {
    @TestConfiguration
    static class Time {
        @Bean @Primary Clock catalogueClock() {
            return Clock.fixed(Instant.parse("2030-01-10T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired CatalogueDiscoveryService discovery;
    @Autowired DestinationService destinationService;
    @Autowired DestinationRepository destinations;
    @Autowired TourPackageRepository packages;
    @Autowired PackageCategoryRepository categories;
    @Autowired DepartureRepository departures;
    @Autowired EntityManager entityManager;

    private String marker;
    private Destination destination;
    private PackageCategory category;

    @BeforeEach void fixture() {
        marker = "TEST-PAGED-" + UUID.randomUUID();
        destination = destination("Alpha");
        category = new PackageCategory();
        category.setName(marker);
        categories.save(category);
    }

    @Test void destinationListsReflectCommittedStyleWritesAfterAnEarlierRead() {
        entityManager.flush();
        entityManager.clear();
        assertTrue(destinationService.findAll().stream().anyMatch(d -> d.getId().equals(destination.getId())));

        Destination added = destination("New city");
        entityManager.flush();
        entityManager.clear();
        assertTrue(destinationService.findAll().stream().anyMatch(d -> d.getId().equals(added.getId())));

        Destination edited = destinations.findById(destination.getId()).orElseThrow();
        edited.setCity("Updated city");
        destinationService.save(edited);
        destinationService.setActive(edited.getId(), false);
        entityManager.flush();
        entityManager.clear();
        Destination fresh = destinationService.findAll().stream()
                .filter(d -> d.getId().equals(destination.getId())).findFirst().orElseThrow();
        assertEquals("Updated city", fresh.getCity());
        assertFalse(fresh.isActive());
    }

    @Test void sqlPagesPreserveEverySortAndStableTies() {
        for (int i = 0; i < 14; i++) tour((i % 2 == 0 ? "alpha " : "Beta ") + i,
                new BigDecimal(i % 3 == 0 ? "100.00" : "200.00"), 3 + i % 3, 8);
        for (String sort : List.of("newest", "oldest", "price-asc", "price-desc", "duration", "name", "unknown")) {
            List<Long> expected = discovery.packages(marker, destination.getId(), null, category.getId(),
                    null, null, null, null, null, sort).stream().map(TourPackage::getId).toList();
            var first = page(null, null, null, null, null, null, sort, 1);
            var last = page(null, null, null, null, null, null, sort, 2);
            var actual = new ArrayList<Long>();
            first.content().forEach(p -> actual.add(p.getId()));
            last.content().forEach(p -> actual.add(p.getId()));
            assertEquals(expected, actual, sort);
            assertEquals(14, first.total());
            assertEquals(9, first.content().size());
            assertEquals(5, last.content().size());
        }
    }

    @Test void allStoredFiltersCombineAndKeepInclusiveBoundaries() {
        tour("Too short", new BigDecimal("100.00"), 3, 4);
        tour("Too expensive", new BigDecimal("250.01"), 4, 4);
        tour("Too small", new BigDecimal("200.00"), 4, 3);
        TourPackage matching = tour("Matching", new BigDecimal("250.00"), 4, 4);
        tour("Too long", new BigDecimal("200.00"), 6, 4);
        var result = page(" ALPHA ", new BigDecimal("250.00"), 4, 5, 4, null, "price-asc", 1);
        assertEquals(List.of(matching.getId()), ids(result));
        assertEquals(1, result.total());
    }

    @Test void searchStillIncludesDescriptionServicesDestinationAndCategory() {
        TourPackage first = tour("First", new BigDecimal("100.00"), 4, 8);
        first.setDescription("Sunrise kayaking");
        TourPackage second = tour("Second", new BigDecimal("100.00"), 4, 8);
        second.setIncludedServices("Guided snorkeling");
        packages.flush();
        assertEquals(List.of(first.getId()), searched("KAYAK").content().stream().map(TourPackage::getId).toList());
        assertEquals(List.of(second.getId()), searched("SNORKELING").content().stream().map(TourPackage::getId).toList());
        assertEquals(2, searched("aLpHa").total());
        assertEquals(2, searched(marker).total());
    }

    @Test void destinationTextTreatsSqlWildcardCharactersAsLiteralText() {
        destination.setCity("Alpha_%!");
        TourPackage matching = tour("Literal", new BigDecimal("100.00"), 4, 8);
        Destination other = destination("AlphaXYZ");
        TourPackage unrelated = tour("Other", new BigDecimal("100.00"), 4, 8);
        unrelated.setDestination(other);
        packages.flush();
        var result = discovery.packagePage(marker, null, "_%!", category.getId(), null, null, null,
                null, null, "name", 1);
        assertEquals(List.of(matching.getId()), ids(result));
    }

    @Test void inactivePackagesAndDestinationsAreExcludedBeforeCounting() {
        TourPackage visible = tour("Visible", new BigDecimal("100.00"), 4, 8);
        tour("Inactive", new BigDecimal("100.00"), 4, 8).setActive(false);
        Destination hidden = destination("Hidden");
        hidden.setActive(false);
        tour("Hidden destination", new BigDecimal("100.00"), 4, 8).setDestination(hidden);
        packages.flush();
        var result = discovery.packagePage(marker, null, null, category.getId(), null, null, null,
                null, null, "name", 1);
        assertEquals(List.of(visible.getId()), ids(result));
        assertEquals(1, result.total());
    }

    @Test void dateFilterRequiresAnEligibleWholeGroupDepartureOnOrAfterTheChosenDate() {
        TourPackage cutoff = tour("Deadline reached", new BigDecimal("100.00"), 4, 8);
        departure(cutoff, "2030-01-13", 8, 8);
        TourPackage tooSmall = tour("Too few seats", new BigDecimal("100.00"), 4, 8);
        departure(tooSmall, "2030-01-15", 3, 8);
        TourPackage eligible = tour("Available", new BigDecimal("100.00"), 4, 8);
        departure(eligible, "2030-01-15", 4, 8);
        departure(eligible, "2030-01-16", 4, 8); // Two departures must not duplicate one package.
        TourPackage beforeSearch = tour("Earlier departure", new BigDecimal("100.00"), 4, 8);
        departure(beforeSearch, "2030-01-14", 4, 8);
        var result = page(null, null, null, null, 4, LocalDate.of(2030, 1, 15), "name", 1);
        assertEquals(List.of(eligible.getId()), ids(result));
        assertEquals(1, result.total());
        assertEquals(discovery.packages(marker, destination.getId(), null, category.getId(), null, null, null,
                4, LocalDate.of(2030, 1, 15), "name").stream().map(TourPackage::getId).toList(), ids(result));
        assertEquals(4, page(null, null, null, null, null, null, "name", 1).total());
    }

    @Test void invalidDepartureInventoryAndReturnDatesDoNotAdvertiseAvailability() {
        TourPackage invalidInventory = tour("Invalid seats", new BigDecimal("100.00"), 4, 8);
        departure(invalidInventory, "2030-01-14", 9, 8);
        TourPackage invalidReturn = tour("Invalid return", new BigDecimal("100.00"), 4, 8);
        departure(invalidReturn, "2030-01-14", 8, 8).setReturnDate(LocalDate.of(2030, 1, 13));
        departures.flush();
        assertTrue(page(null, null, null, null, null, LocalDate.of(2029, 1, 1), "name", 1).content().isEmpty());
    }

    @Test void emptyAndOutOfRangePagesClampWithoutLosingFilters() {
        for (int i = 0; i < 14; i++) tour("Package " + i, new BigDecimal("100.00"), 4, 8);
        assertEquals(1, page(null, null, null, null, null, null, "oldest", -100).currentPage());
        var last = page(null, null, null, null, null, null, "oldest", Integer.MAX_VALUE);
        assertEquals(2, last.currentPage());
        assertEquals(5, last.content().size());
        var empty = page("no-match", null, null, null, null, null, "oldest", Integer.MAX_VALUE);
        assertEquals(1, empty.currentPage());
        assertEquals(1, empty.pageCount());
        assertEquals(0, empty.total());
    }

    @Test void onePublicPageLoadsOnlyItsPackageRowsAndDoesNotLoadDepartureEntities() {
        for (int i = 0; i < 30; i++) {
            TourPackage tour = tour("Package " + i, new BigDecimal("100.00"), 4, 8);
            departure(tour, "2030-01-14", 8, 8);
        }
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var result = page(null, null, null, null, 4, LocalDate.of(2030, 1, 14), "price-asc", 2);
        assertEquals(30, result.total());
        assertEquals(9, result.content().size());
        assertEquals(9, statistics.getEntityStatistics(TourPackage.class.getName()).getLoadCount());
        assertEquals(0, statistics.getEntityStatistics(Departure.class.getName()).getLoadCount());
        assertEquals(2, statistics.getQueryExecutionCount());
    }

    private CatalogueDiscoveryService.CataloguePage searched(String search) {
        return discovery.packagePage(search, destination.getId(), null, category.getId(), null, null,
                null, null, null, "name", 1);
    }

    private CatalogueDiscoveryService.CataloguePage page(String destinationQuery, BigDecimal maxPrice,
            Integer minDays, Integer maxDays, Integer travelers, LocalDate travelDate, String sort, int page) {
        return discovery.packagePage(marker, destination.getId(), destinationQuery, category.getId(), maxPrice,
                minDays, maxDays, travelers, travelDate, sort, page);
    }

    private List<Long> ids(CatalogueDiscoveryService.CataloguePage page) {
        return page.content().stream().map(TourPackage::getId).toList();
    }

    private Destination destination(String city) {
        Destination d = new Destination();
        d.setCity(city);
        d.setCountry(marker);
        return destinations.save(d);
    }

    private TourPackage tour(String name, BigDecimal price, int days, int capacity) {
        TourPackage p = new TourPackage();
        p.setName(marker + " " + name);
        p.setDestination(destination);
        p.setCategory(category);
        p.setBasePrice(price);
        p.setDurationDays(days);
        p.setMaxCapacity(capacity);
        return packages.save(p);
    }

    private Departure departure(TourPackage p, String date, int available, int capacity) {
        Departure d = new Departure();
        d.setTourPackage(p);
        d.setDepartureDate(LocalDate.parse(date));
        d.setReturnDate(d.getDepartureDate().plusDays(3));
        d.setAvailableSeats(available);
        d.setTotalCapacity(capacity);
        return departures.save(d);
    }
}
