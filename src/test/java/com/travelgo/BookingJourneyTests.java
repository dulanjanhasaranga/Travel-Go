package com.travelgo;
import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import com.travelgo.service.BookingJourney;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class BookingJourneyTests {
    @Test void persistedBookingVisaPaymentAndPendingRefundEventsAreChronologicalAndDoNotClaimSettlement(){
        var visas=mock(VisaApplicationRepository.class);var visaHistory=mock(VisaStatusHistoryRepository.class);
        var payments=mock(PaymentRepository.class);var bookingHistory=mock(BookingStatusHistoryRepository.class);
        var refunds=mock(RefundRepository.class);var suppliers=mock(SupplierHoldRepository.class);var cancellations=mock(CancellationRequestRepository.class);
        var journey=new BookingJourney(visas,visaHistory,payments,bookingHistory,refunds,suppliers,cancellations);
        var b=new Booking();b.setId(1L);org.springframework.test.util.ReflectionTestUtils.setField(b,"createdAt",LocalDateTime.of(2030,1,1,10,0));
        var h=new BookingStatusHistory();h.setStatus(BookingStatus.CANCELLED);h.setChangedAt(LocalDateTime.of(2030,1,4,10,0));h.setRemarks("Accepted customer quote");
        when(bookingHistory.findByBooking_IdOrderByChangedAtAsc(1L)).thenReturn(List.of(h));
        var p=new Payment();p.setId(2L);p.setPaidAt(LocalDateTime.of(2030,1,2,10,0));p.setPaymentStatus(PaymentStatus.PAID);p.setPaymentType(PaymentType.VISA_DOCUMENTATION);p.setAmount(new BigDecimal("40.00"));p.setTransactionReference("SIM-TEST");
        when(payments.findByBooking_IdOrderByCreatedAtDesc(1L)).thenReturn(List.of(p));
        var r=new Refund();r.setAmount(new BigDecimal("10.00"));r.setStatus(RefundStatus.REFUND_PENDING);r.setReason("Accepted cancellation quote");
        org.springframework.test.util.ReflectionTestUtils.setField(r,"createdAt",LocalDateTime.of(2030,1,4,11,0));when(refunds.findByPayment_Id(2L)).thenReturn(Optional.of(r));
        var events=journey.events(b);
        assertEquals(List.of("Booking requested","Payment recorded","Booking cancelled","Refund pending"),events.stream().map(BookingJourney.Event::title).toList());
        assertEquals(PaymentStatus.PAID,p.getPaymentStatus());assertNull(r.getProcessedAt());
        verify(refunds,never()).save(any());
    }
    @Test void historicalVisaWithoutTransitionHistoryIsIdentifiedWithoutInventingStages(){
        var visas=mock(VisaApplicationRepository.class);var history=mock(VisaStatusHistoryRepository.class);var payments=mock(PaymentRepository.class);
        var b=new Booking();b.setId(3L);var v=new VisaApplication();v.setId(4L);when(visas.findByBooking_Id(3L)).thenReturn(Optional.of(v));
        var journey=new BookingJourney(visas,history,payments,mock(BookingStatusHistoryRepository.class),mock(RefundRepository.class),mock(SupplierHoldRepository.class),mock(CancellationRequestRepository.class));
        var events=journey.events(b);assertEquals(2,events.size());assertTrue(events.get(1).detail().contains("Earlier status updates are unavailable"));
        assertFalse(events.stream().anyMatch(e->e.title().contains("approved")));
    }
}
