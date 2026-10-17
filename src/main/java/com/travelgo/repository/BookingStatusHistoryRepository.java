package com.travelgo.repository;

import com.travelgo.entity.BookingStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingStatusHistoryRepository extends JpaRepository<BookingStatusHistory, Long> {
    boolean existsByBooking_IdAndStatusNot(Long bookingId, com.travelgo.enums.BookingStatus status);
    List<BookingStatusHistory> findByBooking_IdOrderByChangedAtAsc(Long bookingId);
}
