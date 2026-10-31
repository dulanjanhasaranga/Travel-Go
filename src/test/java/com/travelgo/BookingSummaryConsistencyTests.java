package com.travelgo;

import com.travelgo.entity.*;
import com.travelgo.enums.*;
import com.travelgo.repository.*;
import com.travelgo.service.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingSummaryConsistencyTests {
    PaymentRepository payments;
    RefundRepository refunds;
    WorkflowRules rules;
    BookingSummaryService summaries;
    Booking booking;

    @BeforeEach void setUp() {
        payments=mock(PaymentRepository.class);refunds=mock(RefundRepository.class);rules=mock(WorkflowRules.class);
        summaries=new BookingSummaryService(payments,refunds,rules);
        booking=new Booking();booking.setId(7L);booking.setBookingStatus(BookingStatus.PENDING);
        when(rules.expected(booking,PaymentType.FULL_PACKAGE)).thenReturn(new BigDecimal("100.00"));
        when(payments.findByBooking_Id(7L)).thenReturn(List.of());
    }
    @Test void confirmedWithoutRequiredPaymentsIsFlaggedWithoutChangingStoredStatus() {
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        doThrow(new IllegalStateException("Required payment is missing.")).when(rules).requirePaid(booking,PaymentType.VISA_DOCUMENTATION);
        var summary=summaries.forBooking(booking);
        assertTrue(summary.reviewRequired());assertEquals("Review needed",summary.status());
        assertTrue(summary.explanation().contains("travel consultant"));
        assertEquals(BookingStatus.CONFIRMED,booking.getBookingStatus());
        verifyNoMoreInteractions(refunds);
    }
    @Test void normalUnpaidRequestDoesNotSuggestHistoricalReconciliation() {
        doThrow(new IllegalStateException("Required payment is missing.")).when(rules).requirePaid(booking,PaymentType.VISA_DOCUMENTATION);
        var summary=summaries.forBooking(booking);
        assertFalse(summary.reviewRequired());assertEquals("Not paid",summary.status());
        assertEquals(new BigDecimal("100.00"),summary.packageBalance());
    }
    @Test void paidButUnapprovedVisaCannotDisplayReadyToTravel() {
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        var visa=new VisaApplication();visa.setStatus(VisaStatus.PROCESSING);when(rules.visa(booking)).thenReturn(visa);
        assertTrue(summaries.forBooking(booking).reviewRequired());
        assertEquals(BookingStatus.CONFIRMED,booking.getBookingStatus());
    }
    @Test void validConfirmationKeepsFullyPaidStatus() {
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        var visa=new VisaApplication();visa.setStatus(VisaStatus.APPROVED);when(rules.visa(booking)).thenReturn(visa);
        var summary=summaries.forBooking(booking);
        assertFalse(summary.reviewRequired());assertEquals("Fully Paid",summary.status());
    }
    @Test void mismatchedFinalPaymentRetainsPaidAmountAndRequiresReview() {
        var payment=new Payment();payment.setId(4L);payment.setPaymentType(PaymentType.FULL_PACKAGE);
        payment.setPaymentStatus(PaymentStatus.PAID);payment.setAmount(new BigDecimal("75.00"));
        when(payments.findByBooking_Id(7L)).thenReturn(List.of(payment));when(refunds.findByPayment_Id(4L)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("Payment does not match the booking.")).when(rules).requirePaid(booking,PaymentType.FULL_PACKAGE);
        var summary=summaries.forBooking(booking);
        assertTrue(summary.reviewRequired());assertEquals(new BigDecimal("75.00"),summary.paid());
        assertEquals(new BigDecimal("25.00"),summary.packageBalance());assertEquals(new BigDecimal("75.00"),payment.getAmount());
    }
    @Test void historicalConfirmedBookingWithProcessedPartialRefundIsFlagged() {
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        var visa=new VisaApplication();visa.setStatus(VisaStatus.APPROVED);when(rules.visa(booking)).thenReturn(visa);
        var payment=new Payment();payment.setId(4L);payment.setPaymentType(PaymentType.FULL_PACKAGE);
        payment.setPaymentStatus(PaymentStatus.PAID);payment.setAmount(new BigDecimal("100.00"));
        var refund=new Refund();refund.setAmount(new BigDecimal("20.00"));refund.setStatus(RefundStatus.REFUND_PROCESSED);
        when(payments.findByBooking_Id(7L)).thenReturn(List.of(payment));when(refunds.findByPayment_Id(4L)).thenReturn(Optional.of(refund));
        var summary=summaries.forBooking(booking);
        assertTrue(summary.reviewRequired());assertEquals(new BigDecimal("80.00"),summary.paid());
        assertEquals(new BigDecimal("20.00"),summary.packageBalance());assertEquals(new BigDecimal("100.00"),payment.getAmount());
    }
}
