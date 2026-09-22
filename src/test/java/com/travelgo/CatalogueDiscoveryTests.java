package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.CatalogueDiscoveryService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Catalogue reads and rendered filters use a rollback-only H2 profile, never the application database. */
@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.open-in-view=true"})
@ActiveProfiles("test")
@Transactional
class CatalogueDiscoveryTests {
    @Autowired CatalogueDiscoveryService discovery;
    @Autowired DestinationRepository destinations;
    @Autowired TourPackageRepository packages;
    @Autowired PackageCategoryRepository categories;
    @Autowired HotelRepository hotels;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String marker;
    Destination alpha, beta;
    PackageCategory category;
    TourPackage a, b, c;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        marker = "TEST-DISCOVERY-" + UUID.randomUUID();
        alpha = destination("Alpha", true); beta = destination("Beta", true);
        category = new PackageCategory(); category.setName(marker); categories.save(category);
        a = tour("A", alpha, "100.00", 3, 2, true);
        b = tour("B", alpha, "300.00", 6, 6, true);
        c = tour("C", alpha, "200.00", 4, 4, true);
    }

    Destination destination(String city, boolean active) {
        var d = new Destination(); d.setCity(city); d.setCountry(marker); d.setActive(active);
        return destinations.save(d);
    }
    TourPackage tour(String name, Destination destination, String price, int days, int capacity, boolean active) {
        var p = new TourPackage(); p.setName(marker + " " + name); p.setCategory(category); p.setDestination(destination);
        p.setBasePrice(new BigDecimal(price)); p.setDurationDays(days); p.setMaxCapacity(capacity); p.setActive(active);
        return packages.save(p);
    }
    List<Long> packageIds(String sort) {
        return discovery.packages(marker, alpha.getId(), null, category.getId(), null, null, null, null, null, sort)
            .stream().map(TourPackage::getId).toList();
    }

    @Test void filtersCombineStoredPriceDurationCapacityCategoryAndDestination() {
        var matches = discovery.packages(marker.toLowerCase(), alpha.getId(), " ALPHA ", category.getId(),
            new BigDecimal("250.00"), 4, 5, 4, null, "newest");
        assertEquals(List.of(c.getId()), matches.stream().map(TourPackage::getId).toList());
        assertTrue(discovery.packages(marker, beta.getId(), null, null, null, null, null, null, null, null).isEmpty());
    }

    @Test void allPackageSortsProduceStableDatabaseRecordOrder() {
        assertEquals(List.of(c.getId(), b.getId(), a.getId()), packageIds("newest"));
        assertEquals(List.of(a.getId(), b.getId(), c.getId()), packageIds("oldest"));
        assertEquals(List.of(a.getId(), c.getId(), b.getId()), packageIds("price-asc"));
        assertEquals(List.of(b.getId(), c.getId(), a.getId()), packageIds("price-desc"));
        assertEquals(List.of(a.getId(), c.getId(), b.getId()), packageIds("duration"));
        assertEquals(List.of(a.getId(), b.getId(), c.getId()), packageIds("name"));
    }

    @Test void activitySearchMatchesDescriptionAndIncludedServices() {
        a.setDescription("A sunrise kayak experience"); c.setIncludedServices("Guided snorkeling excursion");
        packages.flush();
        assertEquals(List.of(a.getId()), discovery.packages("KAYAK", alpha.getId(), null, null, null, null, null, null, null, "name").stream().map(TourPackage::getId).toList());
        assertEquals(List.of(c.getId()), discovery.packages("snorkeling", alpha.getId(), null, null, null, null, null, null, null, "name").stream().map(TourPackage::getId).toList());
    }

    @Test void deactivatedRecordsDoNotLeakIntoResultsPricesOrSuggestions() throws Exception {
        var hidden = destination("Hidden", false);
        tour("Inactive package", alpha, "1.00", 1, 20, false);
        tour("Hidden destination package", hidden, "2.00", 1, 20, true);
        assertEquals(3, discovery.packages(marker, null, null, null, null, null, null, null, null, "name").size());
        var visible = discovery.destinations(marker, null, "name");
        assertEquals(List.of(alpha.getId(), beta.getId()), visible.stream().map(Destination::getId).toList());
        var summary = discovery.summaries(visible);
        assertEquals(3, summary.get(alpha.getId()).packageCount());
        assertEquals(0, new BigDecimal("100").compareTo(summary.get(alpha.getId()).startingPrice()));
        assertNull(summary.get(beta.getId()).startingPrice());
        mvc.perform(get("/destinations/" + hidden.getId())).andExpect(status().isNotFound());
        var response = mvc.perform(get("/destinations").param("search", marker)).andExpect(status().isOk()).andReturn();
        assertTrue(response.getResponse().getContentAsString().contains("Alpha, " + marker));
        assertFalse(response.getResponse().getContentAsString().contains("Hidden, " + marker));
    }

    @Test void destinationSortsAndCountryFilterUseActiveRecords() {
        assertEquals(List.of(alpha.getId(), beta.getId()), discovery.destinations(null, marker, "name").stream().map(Destination::getId).toList());
        assertEquals(List.of(beta.getId(), alpha.getId()), discovery.destinations(marker, null, "name-desc").stream().map(Destination::getId).toList());
        assertEquals(List.of(beta.getId(), alpha.getId()), discovery.destinations(marker, null, "newest").stream().map(Destination::getId).toList());
        assertEquals(List.of(alpha.getId(), beta.getId()), discovery.destinations(marker, null, "oldest").stream().map(Destination::getId).toList());
    }

    @Test void paginationKeepsFiltersAndClampsOutOfRangePages() throws Exception {
        for (int i = 0; i < 11; i++) tour("Extra " + i, alpha, "150.00", 4, 4, true);
        tour("Different destination", destination("Alpha", true), "150.00", 4, 4, true);
        var first = mvc.perform(get("/packages").param("destinationId", alpha.getId().toString()).param("search", marker).param("sort", "price-asc"))
            .andExpect(status().isOk()).andExpect(model().attribute("resultCount", 14)).andExpect(model().attribute("pageCount", 2)).andReturn();
        assertEquals(9, ((List<?>) first.getModelAndView().getModel().get("packages")).size());
        assertTrue(first.getResponse().getContentAsString().contains("destinationId=" + alpha.getId()));
        var last = mvc.perform(get("/packages").param("destinationId", alpha.getId().toString()).param("search", marker).param("page", "999"))
            .andExpect(status().isOk()).andExpect(model().attribute("currentPage", 2)).andReturn();
        assertEquals(5, ((List<?>) last.getModelAndView().getModel().get("packages")).size());
        mvc.perform(get("/packages").param("search", "no-match-" + marker).param("page", "-1"))
            .andExpect(status().isOk()).andExpect(model().attribute("currentPage", 1)).andExpect(model().attribute("resultCount", 0));
    }

    @Test void destinationDetailAndHomeRenderCurrentCatalogueData() throws Exception {
        var hotel = new Hotel(); hotel.setName(marker + " HOTEL"); hotel.setAddress("Test address"); hotel.setDestination(alpha); hotel.setPricePerNight(new BigDecimal("40.00")); hotels.save(hotel);
        var page = mvc.perform(get("/destinations/" + alpha.getId())).andExpect(status().isOk()).andExpect(view().name("destination-detail")).andReturn();
        assertTrue(page.getResponse().getContentAsString().contains(hotel.getName()));
        assertTrue(page.getResponse().getContentAsString().contains("/packages/" + a.getId()));
        mvc.perform(get("/packages/" + a.getId())).andExpect(status().isOk()).andExpect(view().name("package-detail"));
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(model().attributeExists("valuePackages", "destinationSummaries"));
    }

    @Test void invalidFilterRangesAndMissingRecordsReturnClearHttpErrors() throws Exception {
        mvc.perform(get("/packages").param("minDays", "8").param("maxDays", "2")).andExpect(status().isBadRequest());
        mvc.perform(get("/packages").param("travelers", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/packages").param("travelers", "1001")).andExpect(status().isBadRequest());
        mvc.perform(get("/packages").param("maxDays", "366")).andExpect(status().isBadRequest());
        mvc.perform(get("/packages").param("maxPrice", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/packages/" + Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(get("/destinations/" + Long.MAX_VALUE)).andExpect(status().isNotFound());
    }
}
