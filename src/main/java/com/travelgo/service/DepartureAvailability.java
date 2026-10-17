package com.travelgo.service;

import com.travelgo.entity.Departure;
import com.travelgo.repository.DepartureRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer-facing options follow the same deadline as transactional booking validation. */
@Service
@Transactional(readOnly = true)
public class DepartureAvailability {
    private final DepartureRepository departures;
    private final Clock clock;

    public DepartureAvailability(DepartureRepository departures, Clock clock) {
        this.departures = departures;
        this.clock = clock;
    }

    public List<Departure> forPackage(Long packageId, int travelers) {
        if (travelers < 1) throw new IllegalArgumentException("Choose at least one traveler.");
        LocalDateTime now = LocalDateTime.now(clock);
        return departures.findByTourPackage_Id(packageId).stream()
                .filter(departure -> departure.getDepartureDate() != null
                        && departure.getReturnDate() != null
                        && !departure.getReturnDate().isBefore(departure.getDepartureDate())
                        && departure.getDepartureDate().atStartOfDay().minusDays(3).isAfter(now))
                .filter(departure -> departure.getAvailableSeats() != null
                        && departure.getTotalCapacity() != null
                        && departure.getAvailableSeats() >= travelers
                        && departure.getAvailableSeats() <= departure.getTotalCapacity())
                .sorted(Comparator.comparing(Departure::getDepartureDate).thenComparing(Departure::getId))
                .toList();
    }

    /** A date-only departure must be strictly after this date to retain its three-day payment window. */
    public LocalDate closedDepartureDateCutoff() {
        return LocalDate.now(clock).plusDays(3);
    }
}
