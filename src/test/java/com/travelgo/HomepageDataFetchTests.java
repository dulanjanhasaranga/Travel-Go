package com.travelgo;

import com.travelgo.controller.PublicController;
import com.travelgo.entity.Destination;
import com.travelgo.entity.PackageCategory;
import com.travelgo.entity.SystemSettings;
import com.travelgo.entity.TourPackage;
import com.travelgo.repository.BookingRepository;
import com.travelgo.repository.DestinationRepository;
import com.travelgo.repository.PackageCategoryRepository;
import com.travelgo.repository.TourPackageRepository;
import com.travelgo.service.*;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Homepage catalogue reads have a bounded query budget; no database or providers are contacted. */
class HomepageDataFetchTests {
    @Test void homepageReadsPackagesOnceAndPreservesVisibleCatalogueOrder() {
        var packageRepository = mock(TourPackageRepository.class);
        var destinationRepository = mock(DestinationRepository.class);
        var categoryRepository = mock(PackageCategoryRepository.class);
        var availability = mock(DepartureAvailability.class);
        var discovery = new CatalogueDiscoveryService(destinationRepository, packageRepository, availability);
        var packageService = new TourPackageService(packageRepository, destinationRepository, categoryRepository,
            mock(BookingRepository.class), mock(WorkflowRules.class), mock(Validator.class), mock(AuditService.class));
        var settingsService = mock(SystemSettingsService.class);
        var categoryService = mock(PackageCategoryService.class);
        var controller = new PublicController(settingsService, mock(DestinationService.class), packageService,
            categoryService, mock(ContactMessageService.class), mock(UserService.class), mock(HotelService.class),
            discovery, availability);

        var alpha = destination(1L, "Alpha", true);
        var beta = destination(2L, "Beta", true);
        var hidden = destination(3L, "Hidden", false);
        var first = tour(beta, true);
        var second = tour(alpha, true);
        when(destinationRepository.findAll()).thenReturn(List.of(beta, hidden, alpha));
        when(packageRepository.findAll()).thenReturn(List.of(first, tour(hidden, true), tour(alpha, false), second));
        var settings = new SystemSettings();
        var categories = List.of(new PackageCategory());
        when(settingsService.getSettings()).thenReturn(settings);
        when(categoryService.findAll()).thenReturn(categories);

        var model = new ExtendedModelMap();
        assertEquals("index", controller.home(model));
        assertEquals(List.of(alpha, beta), model.get("destinations"));
        assertEquals(List.of(first, second), model.get("packages"));
        assertSame(settings, model.get("settings"));
        assertSame(categories, model.get("categories"));
        verify(packageRepository, times(1)).findAll();
        // Reintroducing either unused summary/value-package computation adds an extra catalogue query.
        verifyNoMoreInteractions(packageRepository);
        verifyNoInteractions(availability);
    }

    private Destination destination(long id, String city, boolean active) {
        var destination = new Destination();
        destination.setId(id);
        destination.setCity(city);
        destination.setCountry("Test country");
        destination.setActive(active);
        return destination;
    }

    private TourPackage tour(Destination destination, boolean active) {
        var tour = new TourPackage();
        tour.setDestination(destination);
        tour.setActive(active);
        return tour;
    }
}
