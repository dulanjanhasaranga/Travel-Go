package com.travelgo.service;

import com.travelgo.entity.Departure;
import com.travelgo.entity.TourPackage;
import com.travelgo.repository.DepartureRepository;
import com.travelgo.repository.BookingRepository;
import com.travelgo.repository.TourPackageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class DepartureService {

    private final DepartureRepository departureRepository;
    private final TourPackageRepository tourPackageRepository;
    private final BookingRepository bookingRepository;
    private final WorkflowRules rules;

    public DepartureService(DepartureRepository departureRepository, TourPackageRepository tourPackageRepository,
                            BookingRepository bookingRepository, WorkflowRules rules) {
        this.departureRepository = departureRepository;
        this.tourPackageRepository = tourPackageRepository;
        this.bookingRepository = bookingRepository;
        this.rules = rules;
    }

    public List<Departure> findByTourPackageId(Long packageId) {
        return departureRepository.findByTourPackage_Id(packageId);
    }

    public Departure createDeparture(Long packageId, LocalDate departureDate, LocalDate returnDate, Integer totalCapacity) {
        rules.requireRole("TRAVEL_CONSULTANT");
        if (departureDate == null || returnDate == null || departureDate.isAfter(returnDate)) {
            throw new IllegalArgumentException("Departure date must be before return date");
        }
        if (totalCapacity == null || totalCapacity < 1 || totalCapacity > 1000) {
            throw new IllegalArgumentException("Departure capacity must be between 1 and 1,000 travelers");
        }
        
        TourPackage tourPackage = tourPackageRepository.findForUpdate(packageId)
                .orElseThrow(() -> new IllegalArgumentException("Tour Package not found"));
        if (departureRepository.findByTourPackage_Id(packageId).stream().anyMatch(d -> departureDate.equals(d.getDepartureDate())))
            throw new IllegalArgumentException("This package already has a departure on the selected date.");

        Departure departure = new Departure();
        departure.setTourPackage(tourPackage);
        departure.setDepartureDate(departureDate);
        departure.setReturnDate(returnDate);
        departure.setTotalCapacity(totalCapacity);
        departure.setAvailableSeats(totalCapacity); // Initially, all seats are available

        return departureRepository.save(departure);
    }

    public void deleteDeparture(Long departureId) {
        rules.requireRole("TRAVEL_CONSULTANT");
        Departure departure = departureRepository.findByIdForUpdate(departureId)
                .orElseThrow(() -> new IllegalArgumentException("Departure not found"));
        
        if (bookingRepository.existsByDeparture_Id(departureId)) {
            throw new IllegalStateException("Cannot delete a departure referenced by a booking");
        }
        
        departureRepository.delete(departure);
    }
}
