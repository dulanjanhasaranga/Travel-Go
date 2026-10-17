package com.travelgo.service;

import com.travelgo.entity.Hotel;
import com.travelgo.repository.DestinationRepository;
import com.travelgo.repository.HotelRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class HotelService {

    private final HotelRepository repository;
    private final DestinationRepository destinations;

    public HotelService(HotelRepository repository, DestinationRepository destinations) {
        this.repository = repository;
        this.destinations = destinations;
    }

    public List<Hotel> findAll() {
        return repository.findAll();
    }

    public Optional<Hotel> findById(Long id) {
        return repository.findById(id);
    }

    public Hotel save(Hotel entity) {
        entity.setName(CatalogueInput.required(entity.getName(),"Hotel name"));
        entity.setAddress(CatalogueInput.required(entity.getAddress(),"Address"));
        if(entity.getDestination()==null || entity.getDestination().getId()==null) throw new IllegalArgumentException("Choose an active destination.");
        var destination=destinations.findById(entity.getDestination().getId())
                .orElseThrow(()->new IllegalArgumentException("Choose an active destination."));
        if(!destination.isActive()) throw new IllegalArgumentException("Choose an active destination.");
        entity.setDestination(destination);
        if(entity.getStarRating()!=null && (entity.getStarRating()<1 || entity.getStarRating()>5)) throw new IllegalArgumentException("Hotel rating must be between 1 and 5.");
        CatalogueInput.price(entity.getPricePerNight()); CatalogueInput.text(entity.getDescription(),10000,"Description"); CatalogueInput.image(entity.getImage());
        return repository.save(entity);
    }

    public void deleteById(Long id) {
        setActive(id,false);
    }
    @org.springframework.transaction.annotation.Transactional
    public void setActive(Long id,boolean active) {
        Hotel hotel=repository.findById(id).orElseThrow(()->new IllegalArgumentException("Hotel not found."));
        if(active && !hotel.getDestination().isActive()) throw new IllegalArgumentException("Activate the destination before this hotel.");
        hotel.setActive(active); repository.save(hotel);
    }

    public List<Hotel> findByDestinationId(Long destinationId) {
        return repository.findByDestination_Id(destinationId);
    }
}
