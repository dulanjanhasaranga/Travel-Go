package com.travelgo.service;

import com.travelgo.entity.Booking;
import java.time.LocalDate;

/** The same conservative trip-end boundary is used for staff closeout and customer reviews. */
final class BookingSchedule {
    private BookingSchedule() {}
    static LocalDate endDate(Booking booking) {
        Integer days = booking.getTourPackage().getDurationDays();
        if (booking.getTravelDate() == null || days == null || days < 1)
            throw new IllegalStateException("Review the trip dates before marking this booking complete.");
        LocalDate end = booking.getTravelDate().plusDays(days - 1L);
        if (booking.getDeparture() != null) {
            LocalDate recordedReturn = booking.getDeparture().getReturnDate();
            if (recordedReturn == null || recordedReturn.isBefore(booking.getTravelDate()))
                throw new IllegalStateException("Review the departure return date before marking this booking complete.");
            if (recordedReturn.isAfter(end)) end = recordedReturn;
        }
        return end;
    }
}
