package com.travelgo.repository;

import com.travelgo.entity.BookingNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingNoteRepository extends JpaRepository<BookingNote, Long> {
    List<BookingNote> findByBooking_IdOrderByCreatedAtAsc(Long bookingId);
}
