package com.travelgo.service;

import com.travelgo.entity.Booking;
import com.travelgo.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Timeline built only from persisted events; historical gaps are not invented. */
@Service("bookingJourney")
public class BookingJourney {
    private final VisaApplicationRepository visas;
    private final VisaStatusHistoryRepository history;
    private final PaymentRepository payments;
    public BookingJourney(VisaApplicationRepository visas, VisaStatusHistoryRepository history, PaymentRepository payments) {
        this.visas=visas; this.history=history; this.payments=payments;
    }
    @Transactional(readOnly=true)
    public List<Event> events(Booking booking) {
        var events=new ArrayList<Event>();
        events.add(new Event(booking.getCreatedAt(),"Booking request created","Booking #"+booking.getId()));
        visas.findByBooking_Id(booking.getId()).ifPresent(v -> {
            var records=history.findByVisaApplication_IdOrderByChangedAtAsc(v.getId());
            if (records.isEmpty()) events.add(new Event(v.getCreatedAt(),"Visa application recorded","No detailed history was recorded for this older application."));
            records.forEach(h->events.add(new Event(h.getChangedAt(),h.getStatus().name().replace('_',' '),h.getRemarks())));
        });
        payments.findByBooking_IdOrderByCreatedAtDesc(booking.getId()).stream().filter(p->p.getPaidAt()!=null)
            .forEach(p->events.add(new Event(p.getPaidAt(),"Payment recorded",p.getPaymentType().name().replace('_',' ')+" · $"+p.getAmount()+" · "+p.getTransactionReference())));
        events.sort(Comparator.comparing(Event::at,Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }
    public record Event(LocalDateTime at,String title,String detail) {}
}
