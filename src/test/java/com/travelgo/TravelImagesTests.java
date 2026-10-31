package com.travelgo;

import com.travelgo.config.TravelImages;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Catalogue presentation checks have no database or network dependency. */
class TravelImagesTests {
    private final TravelImages images = new TravelImages();

    @Test void savedAgencyLogoUsesSafeReferencesWithoutDestinationAliases() {
        assertEquals("/images/agency/logo.png", images.logo(" /images/agency/logo.png "));
        assertEquals("/images/europe_pkg_4k.jpg", images.logo("/images/europe_pkg_4k.jpg"));
        assertEquals("https://agency.example/logo.png?v=2", images.logo("https://agency.example/logo.png?v=2"));
        for (String value : new String[] {null, "", "javascript:alert(1)", "data:image/png,test", "//other.example/logo.png",
                "http://other.example/logo.png", "/uploads/logo.png", "/images/../private.png", "/images/%2e%2e/logo.png",
                "https://user:password@other.example/logo.png", "https://", "not a URL"}) {
            assertEquals("/images/travelgo-logo.png", images.logo(value));
        }
    }

    @Test void missingTourCoversMatchTheExistingDestinationAssets() {
        var destinations = java.util.Map.of(
            "Paris, France", "paris", "Tokyo, Japan", "tokyo", "Dubai, UAE", "dubai",
            "Rome, Italy", "rome", "London, UK", "london", "Swiss Alps, Switzerland", "swiss-alps-photo",
            "Santorini, Greece", "santorini-photo", "Maldives", "maldives-photo", "Maasai Mara, Kenya", "kenya-safari-photo");
        destinations.forEach((destination, photo) -> {
            assertEquals("/images/" + photo + ".jpg", images.tour(null, destination));
            assertTrue(Files.isRegularFile(Path.of("src/main/resources/static/images/" + photo + ".jpg")));
        });
    }

    @Test void recordedTourPhotosAndResponsiveMastersArePreserved() {
        assertEquals("/images/tokyo.jpg", images.tour("/images/tokyo_pkg_4k.jpg", "Tokyo, Japan"));
        assertEquals("/images/swiss-alps-photo.jpg", images.tour("/images/swiss_pkg_4k.jpg", "Swiss Alps"));
        assertEquals("/images/custom-tour.jpg", images.tour("/images/custom-tour.jpg", "Swiss Alps"));
        assertEquals("/images/dubai.jpg", images.resolve("https://images.unsplash.com/photo-1512453979798-5ea266f8880c?width=500"));
        assertEquals("/images/dubai.jpg 1400w, /images/dubai-4k.jpg 3264w", images.srcset("/images/dubai.jpg"));
        assertEquals("/images/singapore.jpg 1400w, /images/singapore-4k.jpg 3320w", images.srcset("/images/singapore.jpg"));
        assertEquals("/images/kenya-safari-photo.jpg 1400w, /images/kenya-safari-photo-large.jpg 2820w", images.srcset("/images/kenya_pkg_4k.jpg"));
        assertNull(images.srcset("https://supplier.example/hotel.jpg"));
    }

    @Test void missingAndDestinationPhotosNeverClaimToShowTheNamedHotel() {
        for (String source : new String[] {null, "", "/images/london.jpg", "/images/swiss_pkg_4k.jpg",
                "https://images.unsplash.com/photo-1502602898657-3e91760cbb34",
                "https://images.unsplash.com/photo-unknown-hotel.jpg", "/images/swiss-alps-photo-large.jpg"}) {
            assertFalse(images.hotelPropertyPhoto(source));
            assertEquals("/images/tokyo.jpg", images.hotel(source, "Tokyo, Japan"));
            assertEquals("Property photo unavailable · Destination preview", images.hotelCaption(source, "Tokyo, Japan"));
            assertEquals("Destination preview: Tokyo, Japan", images.hotelAlt(source, "Specific hotel", "Tokyo, Japan"));
        }
    }

    @Test void unknownHotelDestinationUsesAnHonestPlaceholder() {
        assertEquals("/images/property-photo-unavailable.svg", images.hotel(null, "Unlisted city"));
        assertEquals("Property photo unavailable", images.hotelCaption(null, "Unlisted city"));
        assertEquals("Property photo unavailable", images.hotelAlt(null, "Specific hotel", "Unlisted city"));
    }

    @Test void safeAgencySuppliedPropertyPhotosRemainSupported() {
        assertEquals("https://supplier.example/property.jpg?v=2", images.hotel("https://supplier.example/property.jpg?v=2", "Tokyo"));
        assertTrue(images.hotelPropertyPhoto("/images/hotels/property-10.jpg"));
        assertEquals("Specific hotel", images.hotelAlt("/images/hotels/property-10.jpg", "Specific hotel", "Tokyo"));
        assertEquals("Accommodation image supplied by the agency", images.hotelCaption("/images/hotels/property-10.jpg", "Tokyo"));
    }

    @Test void unsafeHistoricalImageReferencesAreNotRendered() {
        for (String source : new String[] {"javascript:alert(1)", "data:image/svg+xml,test", "//evil.example/a.jpg",
                "http://supplier.example/a.jpg", "https://user:secret@supplier.example/a.jpg", "/images/../uploads/a.jpg",
                "/images/%2e%2e/uploads/a.jpg", "/uploads/private.jpg", "not a URI"}) {
            assertEquals("/images/paris.jpg", images.tour(source, "Paris, France"));
            assertFalse(images.hotelPropertyPhoto(source));
            assertEquals("/images/tokyo.jpg", images.hotel(source, "Tokyo, Japan"));
        }
    }
}
