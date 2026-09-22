package com.travelgo.repository;

import com.travelgo.entity.BookingHotel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BookingHotelRepository extends JpaRepository<BookingHotel, Long> {
    Optional<BookingHotel> findByBooking_Id(Long bookingId);
}
