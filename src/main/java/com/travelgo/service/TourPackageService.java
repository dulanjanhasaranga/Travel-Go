package com.travelgo.service;

import com.travelgo.entity.TourPackage;
import com.travelgo.repository.TourPackageRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TourPackageService {

    private final TourPackageRepository repository;
    private final com.travelgo.repository.DestinationRepository destinations;
    private final com.travelgo.repository.PackageCategoryRepository categories;
    private final com.travelgo.repository.BookingRepository bookings;
    private final WorkflowRules rules;
    private final jakarta.validation.Validator validator;

    public TourPackageService(TourPackageRepository repository,
            com.travelgo.repository.DestinationRepository destinations,
            com.travelgo.repository.PackageCategoryRepository categories,
            com.travelgo.repository.BookingRepository bookings, WorkflowRules rules,
            jakarta.validation.Validator validator) {
        this.repository = repository;
        this.destinations=destinations; this.categories=categories; this.bookings=bookings;
        this.rules=rules; this.validator=validator;
    }

    public List<TourPackage> findAll() {
        return repository.findAll();
    }

    public Optional<TourPackage> findById(Long id) {
        return repository.findById(id);
    }

    @org.springframework.transaction.annotation.Transactional
    public TourPackage savePackage(Long id, com.travelgo.dto.PackageRequest request) {
        rules.requireRole("TRAVEL_CONSULTANT");
        if (!validator.validate(request).isEmpty())
            throw new IllegalArgumentException("Enter a package name, valid destination/category, positive price, duration (1–365 days) and capacity (1–1,000). Check field lengths and use at most two price decimals.");
        var destination=destinations.findById(request.destinationId()).orElseThrow(() -> new IllegalArgumentException("Destination not found."));
        var category=categories.findById(request.categoryId()).orElseThrow(() -> new IllegalArgumentException("Category not found."));
        TourPackage p=id==null?new TourPackage():locked(id);
        if (id!=null && bookings.existsByTourPackage_Id(id) &&
                (!p.getDestination().getId().equals(destination.getId()) || !p.getDurationDays().equals(request.durationDays())))
            throw new IllegalStateException("This package has bookings. Keep its destination and duration unchanged; create a separate package for a different itinerary.");
        p.setName(request.name().trim()); p.setCategory(category); p.setDestination(destination);
        p.setDescription(request.description()); p.setBasePrice(request.basePrice()); p.setDurationDays(request.durationDays());
        p.setFlightDetails(request.flightDetails()); p.setIncludedServices(request.includedServices());
        p.setMaxCapacity(request.maxCapacity()); p.setImage(request.image());
        return repository.save(p);
    }

    @org.springframework.transaction.annotation.Transactional
    public void deleteById(Long id) {
        rules.requireRole("TRAVEL_CONSULTANT");
        repository.delete(locked(id));
    }

    @org.springframework.transaction.annotation.Transactional
    public void updateContent(Long id, String itinerary, String exclusions, String audience, boolean active) {
        rules.requireRole("TRAVEL_CONSULTANT");
        if (itinerary==null || exclusions==null || audience==null || itinerary.length()>10000 || exclusions.length()>5000 || audience.length()>5000)
            throw new IllegalArgumentException("Use up to 10,000 characters for the itinerary and 5,000 for each other field.");
        var p=locked(id); p.setItinerary(itinerary.trim()); p.setExcludedServices(exclusions.trim());
        p.setTravelerInformation(audience.trim()); p.setActive(active);
    }

    private TourPackage locked(Long id) {
        return repository.findForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Package not found."));
    }

    public List<TourPackage> searchPackages(String query) {
        return repository.findByNameContainingIgnoreCase(query);
    }

    public List<TourPackage> findByDestinationId(Long destinationId) {
        return repository.findByDestination_Id(destinationId);
    }

    public List<TourPackage> findByCategoryId(Long categoryId) {
        return repository.findByCategory_Id(categoryId);
    }

    public List<TourPackage> filterPackages(String query, Long destinationId, Long categoryId) {
        String cleanQuery = (query != null && !query.trim().isEmpty()) ? query.trim() : null;
        return repository.filterPackages(cleanQuery, destinationId, categoryId);
    }
}
