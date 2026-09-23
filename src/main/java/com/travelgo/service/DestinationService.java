package com.travelgo.service;

import com.travelgo.entity.Destination;
import com.travelgo.repository.DestinationRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DestinationService {

    private final DestinationRepository repository;

    public DestinationService(DestinationRepository repository) {
        this.repository = repository;
    }

    @org.springframework.cache.annotation.Cacheable("destinations")
    public List<Destination> findAll() {
        return repository.findAll();
    }

    public Optional<Destination> findById(Long id) {
        return repository.findById(id);
    }

    public Destination save(Destination entity) {
        entity.setCity(CatalogueInput.required(entity.getCity(),"City"));
        entity.setCountry(CatalogueInput.required(entity.getCountry(),"Country"));
        CatalogueInput.text(entity.getDescription(),10000,"Description"); CatalogueInput.image(entity.getImage());
        return repository.save(entity);
    }

    public void deleteById(Long id) {
        setActive(id, false);
    }
    @org.springframework.transaction.annotation.Transactional
    public void setActive(Long id,boolean active) {
        Destination destination=repository.findById(id).orElseThrow(()->new IllegalArgumentException("Destination not found."));
        destination.setActive(active); repository.save(destination);
    }

    public List<Destination> searchDestinations(String query) {
        return repository.findByCountryContainingIgnoreCaseOrCityContainingIgnoreCase(query, query);
    }
}

