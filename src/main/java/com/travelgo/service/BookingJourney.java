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
    private final BookingStatusHistoryRepository bookingHistory;
    private final RefundRepository refunds;
    private final SupplierHoldRepository suppliers;
    private final CancellationRequestRepository cancellations;
    public BookingJourney(VisaApplicationRepository visas, VisaStatusHistoryRepository history, PaymentRepository payments,
            BookingStatusHistoryRepository bookingHistory,RefundRepository refunds,SupplierHoldRepository suppliers,CancellationRequestRepository cancellations) {
        this.visas=visas; this.history=history; this.payments=payments;
        this.bookingHistory=bookingHistory;this.refunds=refunds;this.suppliers=suppliers;this.cancellations=cancellations;
    }
    @Transactional(readOnly=true)
    public List<Event> events(Booking booking) {
        var events=new ArrayList<Event>();
        events.add(new Event(booking.getCreatedAt(),"Booking requested","Booking #"+booking.getId()));
        bookingHistory.findByBooking_IdOrderByChangedAtAsc(booking.getId()).stream()
            .filter(h->h.getStatus()!=com.travelgo.enums.BookingStatus.PENDING)
            .forEach(h->events.add(new Event(h.getChangedAt(),"Booking "+label(h.getStatus().name()),h.getRemarks())));
        visas.findByBooking_Id(booking.getId()).ifPresent(v -> {
            var records=history.findByVisaApplication_IdOrderByChangedAtAsc(v.getId());
            if (records.isEmpty()) events.add(new Event(v.getCreatedAt(),"Visa application","Earlier status updates are unavailable for this application."));
            records.forEach(h->events.add(new Event(h.getChangedAt(),"Visa "+label(h.getStatus().name()),h.getRemarks())));
        });
        payments.findByBooking_IdOrderByCreatedAtDesc(booking.getId()).stream().filter(p->p.getPaidAt()!=null)
            .forEach(p->{events.add(new Event(p.getPaidAt(),"Payment recorded",p.getPaymentType().name().replace('_',' ')+" · $"+p.getAmount()+" · "+p.getTransactionReference()));
                refunds.findByPayment_Id(p.getId()).ifPresent(r->{boolean processed=r.getStatus()==com.travelgo.enums.RefundStatus.REFUND_PROCESSED;
                    events.add(new Event(processed?r.getProcessedAt():r.getCreatedAt(),processed?"Refund processed":"Refund pending","$"+r.getAmount()+" · "+r.getReason()));});});
        suppliers.findByBooking_Id(booking.getId()).filter(com.travelgo.entity.SupplierHold::hasConfirmationEvidence)
            .ifPresent(h->events.add(new Event(h.getConfirmedAt(),"Supplier confirmation",h.getSupplierName()+" · "+h.getConfirmationReference())));
        cancellations.findByBooking_IdOrderByIdDesc(booking.getId()).forEach(r->{events.add(new Event(r.getCreatedAt(),"Cancellation requested","Request #"+r.getId()));
            if(r.getQuotedAt()!=null)events.add(new Event(r.getQuotedAt(),"Cancellation quote issued","Request #"+r.getId()+" · Quoted refund $"+r.getRefundTotal()));
            if(r.getAcceptedAt()!=null)events.add(new Event(r.getAcceptedAt(),"Cancellation quote accepted","Request #"+r.getId()+" · View the refund status in cancellation details."));});
        events.sort(Comparator.comparing(Event::at,Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }
    public record Event(LocalDateTime at,String title,String detail) {}
    private String label(String value){return value.toLowerCase(java.util.Locale.ROOT).replace('_',' ');}
}
