package com.travelgo;

import com.travelgo.config.TravelImages;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import com.travelgo.service.AdminAccess;
import com.travelgo.service.ContentReadinessService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentReadinessTests {
    AdminAccess access = mock(AdminAccess.class);
    TourPackageRepository packages = mock(TourPackageRepository.class);
    HotelRepository hotels = mock(HotelRepository.class);
    VisaChecklistRepository checklists = mock(VisaChecklistRepository.class);
    TravelPolicyRepository policies = mock(TravelPolicyRepository.class);
    SystemSettingsRepository settings = mock(SystemSettingsRepository.class);
    ContentReadinessService service;
    TourPackage tour;
    Hotel hotel;

    @BeforeEach void fixture() {
        service = new ContentReadinessService(access, packages, hotels, checklists, policies, settings, new TravelImages(),
                Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC), true, true);
        Destination destination = new Destination(); destination.setId(3L); destination.setCity("Tokyo"); destination.setCountry("Japan");
        tour = new TourPackage(); tour.setId(2L); tour.setName("Reviewed tour"); tour.setDestination(destination); tour.setBasePrice(new BigDecimal("100.00"));
        tour.setDurationDays(4); tour.setMaxCapacity(10); tour.setImage("/images/tokyo.jpg"); tour.setDescription("Tour description");
        tour.setItinerary("Day one arrival"); tour.setIncludedServices("Tour services"); tour.setExcludedServices("Flights excluded"); tour.setTravelerInformation("Walking required");
        hotel = new Hotel(); hotel.setId(1L); hotel.setName("Synthetic property"); hotel.setDestination(destination); hotel.setAddress("Synthetic address");
        hotel.setPricePerNight(new BigDecimal("50.00")); hotel.setDescription("Room description"); hotel.setImage("/images/tokyo.jpg");
        when(packages.findAll()).thenReturn(List.of(tour)); when(hotels.findAll()).thenReturn(List.of(hotel));
        when(checklists.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of());
        when(policies.findFirstByPublishedTrueOrderByPublishedAtDescIdDesc()).thenReturn(Optional.empty());
        when(settings.findAll()).thenReturn(List.of());
    }

    @Test void completeRecordedFieldsDoNotBecomeSupplierOrPhotoApproval() {
        var review = service.overview();
        assertEquals(Instant.parse("2026-10-08T12:00:00Z"), review.generatedAt()); assertEquals(0, review.incompleteTours());
        assertEquals(1, review.incompleteHotels()); assertTrue(review.hotels().get(0).gaps().contains("Agency-supplied property photograph"));
        assertTrue(review.hotels().get(0).imageDescription().contains("Destination preview")); assertNull(review.policyId());
        String worksheet = text(service.catalogueWorksheet()); assertTrue(worksheet.contains("Pending agency review"));
        assertTrue(worksheet.contains("Photo owner and license or supplier permission reference"));
        verify(packages, never()).save(any()); verify(hotels, never()).save(any()); verify(policies, never()).save(any());
    }

    @Test void missingContentAndInvalidPricesAreListedWithoutChangingStoredContent() {
        tour.setItinerary(" "); tour.setExcludedServices(null); tour.setBasePrice(BigDecimal.ZERO); hotel.setAddress(null);
        var review = service.overview(); assertEquals(1, review.incompleteTours());
        assertTrue(review.tours().get(0).gaps().containsAll(List.of("Day-by-day itinerary", "Excluded services", "Positive package price")));
        assertTrue(review.hotels().get(0).gaps().contains("Property address")); assertEquals(" ", tour.getItinerary()); assertNull(hotel.getAddress());
    }

    @Test void hashIsStableUntilRelevantContentChangesAndDoesNotUseClock() {
        String original = service.overview().tours().get(0).contentHash();
        assertEquals(64, original.length()); assertEquals(original, service.overview().tours().get(0).contentHash());
        tour.setBasePrice(new BigDecimal("101.00")); assertNotEquals(original, service.overview().tours().get(0).contentHash());
        String priceChanged = service.overview().tours().get(0).contentHash();
        tour.setImage("/images/rome.jpg"); assertNotEquals(priceChanged, service.overview().tours().get(0).contentHash());
    }

    @Test void galleryEvidenceIsExportedAndChangesTheContentReference() {
        String original = service.overview().tours().get(0).contentHash();
        PackageImage image = new PackageImage(); image.setTourPackage(tour); image.setUrl("/images/tokyo.jpg"); image.setCaption("Agency caption"); tour.getGallery().add(image);
        assertNotEquals(original, service.overview().tours().get(0).contentHash()); assertTrue(text(service.catalogueWorksheet()).contains("Agency caption"));
    }

    @Test void csvQuotesMultilineFieldsAndNeutralizesSpreadsheetFormulas() {
        tour.setName(" \t=HYPERLINK(\"https://example.invalid\")"); hotel.setName("+cmd"); tour.setDescription("First line\nSecond, \"quoted\"");
        String csv = text(service.catalogueWorksheet()); assertTrue(csv.startsWith("\uFEFF\"Type\""));
        assertTrue(csv.contains("\"' \t=HYPERLINK(\"\"https://example.invalid\"\")\"")); assertTrue(csv.contains("\"'+cmd\""));
        assertTrue(csv.contains("\"First line\nSecond, \"\"quoted\"\"\""));
    }

    @Test void workflowDownloadStatesCurrentRulesAndRemainsUnsigned() {
        String worksheet = text(service.workflowWorksheet());
        assertTrue(worksheet.contains("awaiting client review and signature")); assertTrue(worksheet.contains("requires visa approval"));
        assertTrue(worksheet.contains("three days before departure")); assertTrue(worksheet.contains("24 hours"));
        assertTrue(worksheet.contains("refunds the documentation charge only")); assertTrue(worksheet.contains("currently use USD"));
        assertTrue(worksheet.contains("Supplier confirmation required in this running configuration: Yes"));
        assertTrue(worksheet.contains("Current published policy: None published"));
    }

    @Test void eachEntryPointChecksCurrentAdministratorPermissionsBeforeReadingRecords() {
        doThrow(new AccessDeniedException("Denied")).when(access).requireAdministrator("SYSTEM_SETTINGS_VIEW", "SYSTEM_SETTINGS_MANAGE");
        assertThrows(AccessDeniedException.class, service::overview); assertThrows(AccessDeniedException.class, service::catalogueWorksheet);
        assertThrows(AccessDeniedException.class, service::workflowWorksheet); verifyNoInteractions(packages, hotels, checklists, policies, settings);
    }

    @Test void inactiveDestinationIsUnavailableAndAppearsInTheContentReference() {
        String original = service.overview().tours().get(0).contentHash(); tour.getDestination().setActive(false);
        assertFalse(service.overview().tours().get(0).available()); assertFalse(service.overview().hotels().get(0).available());
        assertNotEquals(original, service.overview().tours().get(0).contentHash());
    }

    @Test void ambiguousCurrencySettingsStayUnchangedAndAreFlaggedForReview() {
        SystemSettings recorded = new SystemSettings(); recorded.setCurrency("LKR"); when(settings.findAll()).thenReturn(List.of(recorded));
        assertEquals("LKR", service.overview().recordedCurrency()); assertFalse(service.overview().currencyConsistent());
        assertTrue(text(service.workflowWorksheet()).contains("Review required; no amounts have been converted"));
        assertEquals("LKR", recorded.getCurrency()); verify(settings, never()).save(any());
        when(settings.findAll()).thenReturn(List.of(recorded, new SystemSettings()));
        assertEquals("Multiple settings records", service.overview().recordedCurrency()); assertFalse(service.overview().currencyConsistent());
    }

    private String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
}
