package com.travelgo.service;

import com.travelgo.entity.Departure;
import com.travelgo.entity.TourPackage;
import com.travelgo.repository.DepartureRepository;
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

    public DepartureService(DepartureRepository departureRepository, TourPackageRepository tourPackageRepository) {
        this.departureRepository = departureRepository;
        this.tourPackageRepository = tourPackageRepository;
    }

    public List<Departure> findByTourPackageId(Long packageId) {
        return departureRepository.findByTourPackage_Id(packageId);
    }

    public Departure createDeparture(Long packageId, LocalDate departureDate, LocalDate returnDate, Integer totalCapacity) {
        if (departureDate.isAfter(returnDate)) {
            throw new IllegalArgumentException("Departure date must be before return date");
        }
        
        TourPackage tourPackage = tourPackageRepository.findById(packageId)
                .orElseThrow(() -> new IllegalArgumentException("Tour Package not found"));

        Departure departure = new Departure();
        departure.setTourPackage(tourPackage);
        departure.setDepartureDate(departureDate);
        departure.setReturnDate(returnDate);
        departure.setTotalCapacity(totalCapacity);
        departure.setAvailableSeats(totalCapacity); // Initially, all seats are available

        return departureRepository.save(departure);
    }

    public void deleteDeparture(Long departureId) {
        Departure departure = departureRepository.findById(departureId)
                .orElseThrow(() -> new IllegalArgumentException("Departure not found"));
        
        // We only allow deleting if no seats are taken (or we could just let DB constraint fail if bookings exist)
        if (departure.getAvailableSeats() < departure.getTotalCapacity()) {
            throw new IllegalStateException("Cannot delete a departure that has active bookings");
        }
        
        departureRepository.delete(departure);
    }
}
