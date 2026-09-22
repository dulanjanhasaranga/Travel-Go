package com.travelgo.service;

import com.travelgo.entity.BookingHotel;
import com.travelgo.repository.BookingHotelRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class BookingHotelService {

    private final BookingHotelRepository repository;

    public BookingHotelService(BookingHotelRepository repository) {
        this.repository = repository;
    }

    public List<BookingHotel> findAll() {
        return repository.findAll();
    }

    public Optional<BookingHotel> findById(Long id) {
        return repository.findById(id);
    }

    public BookingHotel save(BookingHotel entity) {
        return repository.save(entity);
    }

    public void deleteById(Long id) {
        repository.deleteById(id);
    }

    public Optional<BookingHotel> findByBookingId(Long bookingId) {
        return repository.findByBooking_Id(bookingId);
    }
}
