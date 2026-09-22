package com.travelgo.service;

import com.travelgo.entity.Destination;
import com.travelgo.entity.TourPackage;
import com.travelgo.repository.DestinationRepository;
import com.travelgo.repository.TourPackageRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public discovery reads current catalogue records; capacity is per booking, not live inventory. */
@Service
@Transactional(readOnly = true)
public class CatalogueDiscoveryService {
    private final DestinationRepository destinations;
    private final TourPackageRepository packages;
    private final com.travelgo.repository.DepartureRepository departures;

    public CatalogueDiscoveryService(DestinationRepository destinations, TourPackageRepository packages, com.travelgo.repository.DepartureRepository departures) {
        this.destinations = destinations;
        this.packages = packages;
        this.departures = departures;
    }

    public List<Destination> destinations(String search, String country, String sort) {
        Comparator<Destination> order = Comparator.comparing(Destination::getCity, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Destination::getId);
        if ("newest".equals(sort)) order = Comparator.comparing(Destination::getId).reversed();
        if ("oldest".equals(sort)) order = Comparator.comparing(Destination::getId);
        if ("name-desc".equals(sort)) order = Comparator.comparing(Destination::getCity, String.CASE_INSENSITIVE_ORDER).reversed().thenComparing(Destination::getId);
        String query = clean(search);
        return destinations.findAll().stream().filter(Destination::isActive)
                .filter(d -> clean(d.getCity() + ", " + d.getCountry()).contains(query))
                .filter(d -> country == null || country.isBlank() || d.getCountry().equalsIgnoreCase(country.trim()))
                .sorted(order).toList();
    }

    public List<TourPackage> packages(String search, Long destinationId, String destinationQuery, Long categoryId,
            BigDecimal maxPrice, Integer minDays, Integer maxDays, Integer travelers, java.time.LocalDate travelDate, String sort) {
        Comparator<TourPackage> order = switch (sort == null ? "" : sort) {
            case "price-asc" -> Comparator.comparing(TourPackage::getBasePrice);
            case "price-desc" -> Comparator.comparing(TourPackage::getBasePrice).reversed();
            case "duration" -> Comparator.comparing(TourPackage::getDurationDays);
            case "name" -> Comparator.comparing(TourPackage::getName, String.CASE_INSENSITIVE_ORDER);
            case "oldest" -> Comparator.comparing(TourPackage::getId);
            default -> Comparator.comparing(TourPackage::getId).reversed();
        };
        String query = clean(destinationQuery);
        return packages.filterPackages(search == null || search.isBlank() ? null : search.trim(), destinationId, categoryId)
                .stream().filter(p -> p.isActive() && p.getDestination().isActive())
                .filter(p -> clean(p.getDestination().getCity() + ", " + p.getDestination().getCountry()).contains(query))
                .filter(p -> maxPrice == null || p.getBasePrice().compareTo(maxPrice) <= 0)
                .filter(p -> minDays == null || p.getDurationDays() >= minDays)
                .filter(p -> maxDays == null || p.getDurationDays() <= maxDays)
                .filter(p -> travelers == null || p.getMaxCapacity() >= travelers)
                .filter(p -> travelDate == null || departures.findByTourPackage_Id(p.getId()).stream().anyMatch(d -> !d.getDepartureDate().isBefore(travelDate) && d.getAvailableSeats() > 0))
                .sorted(order.thenComparing(TourPackage::getId)).toList();
    }

    public Map<Long, DestinationSummary> summaries(List<Destination> visibleDestinations) {
        var result = new LinkedHashMap<Long, DestinationSummary>();
        visibleDestinations.forEach(d -> result.put(d.getId(), new DestinationSummary(0, null)));
        for (TourPackage p : packages.findAll()) {
            if (!p.isActive() || !p.getDestination().isActive() || !result.containsKey(p.getDestination().getId())) continue;
            var previous = result.get(p.getDestination().getId());
            BigDecimal lowest = previous.startingPrice() == null ? p.getBasePrice() : previous.startingPrice().min(p.getBasePrice());
            result.put(p.getDestination().getId(), new DestinationSummary(previous.packageCount() + 1, lowest));
        }
        return result;
    }

    private String clean(String text) { return text == null ? "" : text.trim().toLowerCase(Locale.ROOT); }

    public record DestinationSummary(int packageCount, BigDecimal startingPrice) { }
}
