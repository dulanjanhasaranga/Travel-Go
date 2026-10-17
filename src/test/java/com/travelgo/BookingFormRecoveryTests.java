package com.travelgo;

import com.travelgo.controller.CustomerBookingController.TravelerFormRow;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Failed submissions exercise real rollback and rendered recovery, using guarded H2 only. */
@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.open-in-view=true"})
@ActiveProfiles("test")
@Import({WorkflowIntegrationTests.Time.class, TestDatabaseSafetyConfiguration.class})
class BookingFormRecoveryTests {
    @Autowired WebApplicationContext context;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WorkflowIntegrationTests.MutableClock clock;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired DestinationRepository destinations;
    @Autowired PackageCategoryRepository categories;
    @Autowired TourPackageRepository packages;
    @Autowired HotelRepository hotels;
    @Autowired DepartureRepository departures;
    @Autowired BookingRepository bookings;
    @Autowired TravelerRepository travelers;
    @Autowired BookingHotelRepository bookingHotels;
    @Autowired SupplierHoldRepository holds;
    @Autowired NotificationRepository notifications;
    @Autowired OutboundEmailRepository emails;
    @Autowired TravelPolicyRepository policies;
    @Autowired com.travelgo.service.SystemSettingsService settings;
    MockMvc mvc;
    TransactionTemplate tx;
    User customer;
    TourPackage tour;
    Hotel hotel;
    Departure departure;
    final LocalDate travelDate = LocalDate.of(2030, 2, 1);

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        clock.instant = Instant.parse("2030-01-01T12:00:00Z");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).alwaysDo(result -> {
            if (result.getResponse().getStatus() >= 500 && result.getResolvedException() != null) {
                throw new AssertionError("Unexpected MVC failure while checking booking-form recovery", result.getResolvedException());
            }
        }).build();
        tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            String marker = "TEST-FORM-RECOVERY-" + UUID.randomUUID();
            // Public/customer pages initialize settings under the administrator reference-role lock.
            // This creates reference data only; no administrator account is seeded.
            if (roles.findByRoleName("ADMIN").isEmpty()) {
                Role adminRole = new Role(); adminRole.setRoleName("ADMIN"); roles.save(adminRole);
            }
            Role role = roles.findByRoleName("CUSTOMER").orElseGet(() -> {
                Role value = new Role(); value.setRoleName("CUSTOMER"); return roles.save(value);
            });
            customer = users.save(new User("Synthetic customer", marker + "@example.invalid", "unused-test-password", role));
            Destination destination = new Destination(); destination.setCity(marker); destination.setCountry("Test destination");
            destinations.save(destination);
            PackageCategory category = new PackageCategory(); category.setName(marker); categories.save(category);
            tour = new TourPackage(); tour.setName(marker); tour.setDestination(destination); tour.setCategory(category);
            tour.setBasePrice(new BigDecimal("150.00")); tour.setDurationDays(5); tour.setMaxCapacity(10); packages.save(tour);
            hotel = new Hotel(); hotel.setName(marker + " hotel"); hotel.setDestination(destination);
            hotel.setAddress("Synthetic address"); hotel.setPricePerNight(new BigDecimal("25.00")); hotels.save(hotel);
            departure = scheduled(travelDate);
            scheduled(travelDate.plusMonths(1));
        });
        assertEquals("ACTIVE", settings.getSettings().getWebsiteStatus());
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    Departure scheduled(LocalDate date) {
        Departure value = new Departure(); value.setTourPackage(tour); value.setDepartureDate(date);
        value.setReturnDate(date.plusDays(4)); value.setTotalCapacity(10); value.setAvailableSeats(10);
        return departures.save(value);
    }

    MockHttpServletRequestBuilder request(String token) {
        return request(token, "PASSPORT-ONE", "PASSPORT-TWO");
    }

    MockHttpServletRequestBuilder request(String token, String... passportValues) {
        return post("/customer/bookings/create").with(user(customer.getEmail()).roles("CUSTOMER")).with(csrf())
                .param("requestToken", token).param("packageId", tour.getId().toString())
                .param("travelDate", travelDate.toString()).param("numberOfTravelers", "2")
                .param("hotelId", hotel.getId().toString()).param("travelerNames", "First <&> traveler", "Second traveler")
                .param("travelerPassports", passportValues)
                .param("travelerDobs", "1990-01-01", "1992-02-02").param("travelerGenders", "Male", "Female")
                .param("travelerNationalities", "First nationality", "Second nationality");
    }

    long[] counts() {
        return new long[]{bookings.count(), travelers.count(), bookingHotels.count(), holds.count(), notifications.count(), emails.count()};
    }

    @SuppressWarnings("unchecked")
    List<TravelerFormRow> rows(MvcResult response) {
        return (List<TravelerFormRow>) response.getModelAndView().getModel().get("submittedTravelers");
    }

    @Test void mismatchedTravelerListsRetainEveryProvidedFieldWithoutPartialWritesOrSessionFlash() throws Exception {
        String token = UUID.randomUUID().toString(); long[] before = counts();
        var submission = request(token, "ONLY-ONE");
        MvcResult result = mvc.perform(submission).andExpect(status().isOk()).andExpect(view().name("customer/booking-form"))
                .andExpect(model().attribute("requestToken", token)).andExpect(model().attribute("submittedTravelerCount", 2))
                .andExpect(model().attribute("submittedTravelDate", travelDate)).andExpect(model().attribute("submittedHotelId", hotel.getId()))
                .andExpect(model().attribute("errorMessage", "Complete the details for every traveler.")).andReturn();
        assertEquals(2, rows(result).size()); assertEquals("ONLY-ONE", rows(result).get(0).passport());
        assertNull(rows(result).get(1).passport()); assertEquals("Second nationality", rows(result).get(1).nationality());
        String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("First &lt;&amp;&gt; traveler")); assertFalse(html.contains("First <&> traveler"));
        assertFalse(html.contains("value=\"PASSPORT-TWO\""));
        assertTrue(html.contains("id=\"traveler-passport-2\""));
        assertTrue(result.getFlashMap().isEmpty());
        assertArrayEquals(before, counts()); assertEquals(10, departures.findById(departure.getId()).orElseThrow().getAvailableSeats());
    }

    @Test void departureThatFillsAfterFormOpenedRetainsDetailsAndRefreshesChoices() throws Exception {
        MvcResult opened = mvc.perform(get("/packages/" + tour.getId() + "/book").with(user(customer.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isOk()).andExpect(view().name("customer/booking-form")).andReturn();
        String token = opened.getModelAndView().getModel().get("requestToken").toString();
        tx.executeWithoutResult(status -> { var full = departures.findById(departure.getId()).orElseThrow(); full.setAvailableSeats(0); });
        long[] before = counts();
        MvcResult result = mvc.perform(request(token)).andExpect(status().isOk()).andExpect(view().name("customer/booking-form"))
                .andExpect(model().attribute("submittedDepartureAvailable", false)).andExpect(model().attribute("requestToken", token)).andReturn();
        assertEquals("PASSPORT-TWO", rows(result).get(1).passport());
        assertTrue(result.getResponse().getContentAsString().contains("unavailable; choose another departure"));
        assertTrue(result.getResponse().getContentAsString().contains("value=\"2030-03-01\""));
        assertArrayEquals(before, counts()); assertEquals(0, departures.findById(departure.getId()).orElseThrow().getAvailableSeats());
    }

    @Test void unavailableHotelRollsBackBookingTravelersAndSeatReservationWhileKeepingSelectionVisible() throws Exception {
        tx.executeWithoutResult(status -> hotels.findById(hotel.getId()).orElseThrow().setActive(false));
        long[] before = counts();
        MvcResult result = mvc.perform(request(UUID.randomUUID().toString())).andExpect(status().isOk())
                .andExpect(view().name("customer/booking-form")).andExpect(model().attribute("submittedHotelAvailable", false)).andReturn();
        assertTrue(result.getResponse().getContentAsString().contains("Selected hotel is unavailable"));
        assertEquals("PASSPORT-ONE", rows(result).get(0).passport());
        assertArrayEquals(before, counts()); assertEquals(10, departures.findById(departure.getId()).orElseThrow().getAvailableSeats());
    }

    @Test void changedPolicyIsShownForFreshAcceptanceWithoutLosingTravelerDetails() throws Exception {
        TravelPolicy old = publishedPolicy("First synthetic revision", LocalDateTime.of(2099, 1, 1, 0, 0));
        TravelPolicy current = publishedPolicy("Second synthetic revision", LocalDateTime.of(2099, 1, 2, 0, 0));
        try {
            long[] before = counts();
            MvcResult result = mvc.perform(request(UUID.randomUUID().toString()).param("policyAccepted", "true").param("policyId", old.getId().toString()))
                    .andExpect(status().isOk()).andExpect(view().name("customer/booking-form")).andReturn();
            assertEquals(current.getId(), ((TravelPolicy) result.getModelAndView().getModel().get("currentTravelPolicy")).getId());
            assertEquals("PASSPORT-TWO", rows(result).get(1).passport());
            String html = result.getResponse().getContentAsString();
            assertTrue(html.contains("name=\"policyId\" value=\"" + current.getId() + "\""));
            assertFalse(html.contains("name=\"policyAccepted\" value=\"true\" checked"));
            assertArrayEquals(before, counts()); assertEquals(10, departures.findById(departure.getId()).orElseThrow().getAvailableSeats());
        } finally { tx.executeWithoutResult(status -> policies.deleteAllById(List.of(old.getId(), current.getId()))); }
    }

    TravelPolicy publishedPolicy(String title, LocalDateTime publishedAt) {
        return tx.execute(status -> {
            TravelPolicy p = new TravelPolicy(); p.setTitle(title); p.setTerms("Synthetic terms for isolated rendering tests only.");
            p.setPrivacy("Synthetic privacy policy."); p.setCancellation("Synthetic cancellation policy."); p.setPublished(true);
            p.setCreatedAt(LocalDateTime.of(2030, 1, 1, 12, 0)); p.setPublishedAt(publishedAt);
            p.setCreatedBy(customer); p.setPublishedBy(customer); return policies.saveAndFlush(p);
        });
    }

    @Test void successfulRetryKeepsIdempotencyKeyAndRepeatedSubmissionCreatesOneBooking() throws Exception {
        String token = UUID.randomUUID().toString();
        var invalid = request(token, "ONLY-ONE");
        mvc.perform(invalid).andExpect(status().isOk()).andExpect(model().attribute("requestToken", token));
        mvc.perform(request(token)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/customer/bookings"));
        long[] after = counts();
        mvc.perform(request(token)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/customer/bookings"));
        assertArrayEquals(after, counts());
        var created = bookings.findByUser_IdOrderByCreatedAtDesc(customer.getId()); assertEquals(1, created.size());
        assertEquals(customer.getId() + ":" + token, created.get(0).getRequestKey());
        assertEquals(8, departures.findById(departure.getId()).orElseThrow().getAvailableSeats());
    }

    @Test void deactivatedPackageReturnsSafeCatalogueMessageInsteadOfRenderingUnavailableDetails() throws Exception {
        tx.executeWithoutResult(status -> packages.findById(tour.getId()).orElseThrow().setActive(false));
        long[] before = counts();
        MvcResult result = mvc.perform(request(UUID.randomUUID().toString())).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/packages")).andReturn();
        assertEquals(Set.of("errorMessage"), result.getFlashMap().keySet());
        assertArrayEquals(before, counts());
    }
}
