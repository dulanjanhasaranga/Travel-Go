package com.travelgo;

import com.travelgo.entity.Destination;
import com.travelgo.entity.Hotel;
import com.travelgo.entity.PackageCategory;
import com.travelgo.repository.DestinationRepository;
import com.travelgo.repository.HotelRepository;
import com.travelgo.repository.PackageCategoryRepository;
import com.travelgo.service.DestinationService;
import com.travelgo.service.HotelService;
import com.travelgo.service.PackageCategoryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the staff catalogue service contract using the rollback-only H2 test profile.
 * It deliberately exercises services directly, so a controller binding cannot bypass the
 * validation or turn a deactivation into a physical delete.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.jpa.open-in-view=false"})
@ActiveProfiles("test")
@Transactional
class CatalogueManagementSafetyTests {
    @Autowired DestinationService destinationService;
    @Autowired HotelService hotelService;
    @Autowired PackageCategoryService categoryService;
    @Autowired DestinationRepository destinations;
    @Autowired HotelRepository hotels;
    @Autowired PackageCategoryRepository categories;
    @PersistenceContext EntityManager entityManager;

    private String marker;

    @BeforeEach
    void setUp() {
        marker = "TEST-CATALOGUE-" + UUID.randomUUID();
    }

    @Test
    void destinationInputIsNormalizedAndDeactivationKeepsTheStoredRecord() {
        Destination destination = new Destination();
        destination.setCountry("  " + marker + " country  ");
        destination.setCity("  " + marker + " city  ");
        destination.setDescription("A valid destination description");
        destination.setImage("https://images.example.invalid/hero.jpg");
        Destination saved = destinationService.save(destination);
        long recordsBefore = destinations.count();

        assertEquals(marker + " country", saved.getCountry());
        assertEquals(marker + " city", saved.getCity());
        destinationService.deleteById(saved.getId());
        entityManager.flush();
        entityManager.clear();

        Destination inactive = destinations.findById(saved.getId()).orElseThrow();
        assertFalse(inactive.isActive());
        assertEquals(recordsBefore, destinations.count());

        destinationService.setActive(saved.getId(), true);
        entityManager.flush();
        entityManager.clear();
        assertTrue(destinations.findById(saved.getId()).orElseThrow().isActive());

        Destination missingCity = new Destination();
        missingCity.setCountry(marker);
        assertThrows(IllegalArgumentException.class, () -> destinationService.save(missingCity));

        Destination unsafeImage = new Destination();
        unsafeImage.setCountry(marker);
        unsafeImage.setCity(marker);
        unsafeImage.setImage("http://images.example.invalid/hero.jpg");
        assertThrows(IllegalArgumentException.class, () -> destinationService.save(unsafeImage));

        Destination traversalImage = new Destination();
        traversalImage.setCountry(marker);
        traversalImage.setCity(marker);
        traversalImage.setImage("/images/../private.jpg");
        assertThrows(IllegalArgumentException.class, () -> destinationService.save(traversalImage));
    }

    @Test
    void categoryValidationAndDeactivationPreserveTheCategoryForExistingPackages() {
        PackageCategory category = new PackageCategory();
        category.setName("  " + marker + "  ");
        category.setDescription("An approved category");
        PackageCategory saved = categoryService.save(category);
        long recordsBefore = categories.count();

        assertEquals(marker, saved.getName());
        categoryService.deleteById(saved.getId());
        entityManager.flush();
        entityManager.clear();
        assertFalse(categories.findById(saved.getId()).orElseThrow().isActive());
        assertEquals(recordsBefore, categories.count());

        categoryService.setActive(saved.getId(), true);
        entityManager.flush();
        entityManager.clear();
        assertTrue(categories.findById(saved.getId()).orElseThrow().isActive());

        PackageCategory blank = new PackageCategory();
        blank.setName("   ");
        assertThrows(IllegalArgumentException.class, () -> categoryService.save(blank));

        PackageCategory excessiveDescription = new PackageCategory();
        excessiveDescription.setName(marker + " long");
        excessiveDescription.setDescription("x".repeat(256));
        assertThrows(IllegalArgumentException.class, () -> categoryService.save(excessiveDescription));
    }

    @Test
    void hotelValidationUsesThePersistedDestinationAndSoftDeactivationIsReversible() {
        Destination activeDestination = destination(true);
        Hotel saved = hotel(activeDestination);
        saved = hotelService.save(saved);
        Long savedHotelId = saved.getId();
        long recordsBefore = hotels.count();

        hotelService.deleteById(savedHotelId);
        entityManager.flush();
        entityManager.clear();
        assertFalse(hotels.findById(savedHotelId).orElseThrow().isActive());
        assertEquals(recordsBefore, hotels.count());
        hotelService.setActive(savedHotelId, true);

        Hotel invalidRating = hotel(activeDestination);
        invalidRating.setStarRating(6);
        assertThrows(IllegalArgumentException.class, () -> hotelService.save(invalidRating));
        Hotel invalidPrice = hotel(activeDestination);
        invalidPrice.setPricePerNight(new BigDecimal("0.00"));
        assertThrows(IllegalArgumentException.class, () -> hotelService.save(invalidPrice));

        Destination inactiveDestination = destination(false);
        assertThrows(IllegalArgumentException.class, () -> hotelService.save(hotel(inactiveDestination)));

        destinationService.deleteById(activeDestination.getId());
        entityManager.flush();
        entityManager.clear();
        Destination forgedActiveReference = new Destination();
        forgedActiveReference.setId(activeDestination.getId());
        forgedActiveReference.setActive(true);
        assertThrows(IllegalArgumentException.class, () -> hotelService.save(hotel(forgedActiveReference)));

        hotelService.deleteById(savedHotelId);
        assertThrows(IllegalArgumentException.class, () -> hotelService.setActive(savedHotelId, true));
        destinationService.setActive(activeDestination.getId(), true);
        hotelService.setActive(savedHotelId, true);
        entityManager.flush();
        entityManager.clear();
        assertTrue(hotels.findById(savedHotelId).orElseThrow().isActive());
    }

    private Destination destination(boolean active) {
        Destination destination = new Destination();
        destination.setCountry(marker + " country");
        destination.setCity(marker + " city " + UUID.randomUUID());
        destination.setActive(active);
        return destinationService.save(destination);
    }

    private Hotel hotel(Destination destination) {
        Hotel hotel = new Hotel();
        hotel.setName(marker + " hotel " + UUID.randomUUID());
        hotel.setAddress("123 Test Road");
        hotel.setDestination(destination);
        hotel.setStarRating(4);
        hotel.setPricePerNight(new BigDecimal("99.99"));
        hotel.setImage("/images/hotel.jpg");
        return hotel;
    }
}
