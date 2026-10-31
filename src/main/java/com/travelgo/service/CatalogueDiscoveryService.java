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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Public discovery reads current catalogue records and the agency's scheduled departure inventory. */
@Service
@Transactional(readOnly = true)
public class CatalogueDiscoveryService {
    private final DestinationRepository destinations;
    private final TourPackageRepository packages;
    private final DepartureAvailability departureAvailability;

    public CatalogueDiscoveryService(DestinationRepository destinations, TourPackageRepository packages, DepartureAvailability departureAvailability) {
        this.destinations = destinations;
        this.packages = packages;
        this.departureAvailability = departureAvailability;
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
                .filter(p -> travelDate == null || departureAvailability.forPackage(p.getId(), travelers == null ? 1 : travelers)
                        .stream().anyMatch(d -> !d.getDepartureDate().isBefore(travelDate)))
                .sorted(order.thenComparing(TourPackage::getId)).toList();
    }

    /** Public result pages filter and limit rows in SQL; related departure rows are checked with EXISTS. */
    public CataloguePage packagePage(String search, Long destinationId, String destinationQuery, Long categoryId,
            BigDecimal maxPrice, Integer minDays, Integer maxDays, Integer travelers,
            java.time.LocalDate travelDate, String sort, int requestedPage) {
        String query = search == null || search.isBlank() ? null : search.trim();
        // Destination text previously used String.contains, so SQL wildcard characters must remain literal.
        String destinationPattern = "%" + clean(destinationQuery).replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
        java.time.LocalDate closedDepartureDate = departureAvailability.closedDepartureDateCutoff();
        int departureTravelers = travelers == null ? 1 : travelers;
        long total = packages.countCatalogue(query, destinationId, categoryId, destinationPattern, maxPrice,
                minDays, maxDays, travelers, travelDate, closedDepartureDate, departureTravelers);
        int pageCount = (int)Math.max(1L, (total + 8L) / 9L);
        int currentPage = Math.max(1, Math.min(requestedPage, pageCount));
        if (total == 0) return new CataloguePage(List.of(), total, currentPage, pageCount);
        Sort order = switch (sort == null ? "" : sort) {
            case "price-asc" -> Sort.by("basePrice");
            case "price-desc" -> Sort.by(Sort.Direction.DESC, "basePrice");
            case "duration" -> Sort.by("durationDays");
            case "name" -> Sort.by(Sort.Order.asc("name").ignoreCase());
            case "oldest" -> Sort.by("id");
            default -> Sort.by(Sort.Direction.DESC, "id");
        };
        order = order.and(Sort.by("id"));
        List<TourPackage> content = packages.findCatalogue(query, destinationId, categoryId, destinationPattern,
                maxPrice, minDays, maxDays, travelers, travelDate, closedDepartureDate, departureTravelers,
                PageRequest.of(currentPage - 1, 9, order));
        return new CataloguePage(content, total, currentPage, pageCount);
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
    public record CataloguePage(List<TourPackage> content, long total, int currentPage, int pageCount) { }
}
