package com.travelgo.service;

import com.travelgo.entity.Traveler;
import com.travelgo.repository.TravelerRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TravelerService {

    private final TravelerRepository repository;

    public TravelerService(TravelerRepository repository) {
        this.repository = repository;
    }

    public List<Traveler> findByBookingId(Long id) { return repository.findByBooking_Id(id); }

    public List<Traveler> findAll() {
        return repository.findAll();
    }

    public Optional<Traveler> findById(Long id) {
        return repository.findById(id);
    }

    public Traveler save(Traveler entity) {
        return repository.save(entity);
    }

    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
