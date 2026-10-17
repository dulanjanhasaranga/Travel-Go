package com.travelgo;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.entity.Refund;
import com.travelgo.enums.BookingStatus;
import com.travelgo.enums.PaymentStatus;
import com.travelgo.enums.RefundStatus;
import com.travelgo.repository.BookingNoteRepository;
import com.travelgo.repository.PaymentRepository;
import com.travelgo.repository.RefundRepository;
import com.travelgo.service.ReportService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportServiceTests {
    private final BookingNoteRepository notes = mock(BookingNoteRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final ReportService reports = new ReportService(notes, payments, refunds);
    private Booking booking;

    @BeforeEach
    void setUp() {
        booking = new Booking();
        booking.setId(1L);
        booking.setBookingStatus(BookingStatus.CANCELLED);
        booking.setCreatedAt(LocalDateTime.of(2030, 1, 1, 10, 0));
        booking.setUpdatedAt(booking.getCreatedAt().plusDays(2));
        when(notes.findByBooking_IdOrderByCreatedAtAsc(booking.getId())).thenReturn(List.of());
    }

    @Test
    void bothKpiFormatsSubtractProcessedPartialRefundsWithoutChangingTheLedger() throws Exception {
        Payment paid = payment(10L, PaymentStatus.PAID, "120.00");
        Payment failed = payment(11L, PaymentStatus.FAILED, "999.00");
        when(payments.findByBooking_Id(booking.getId())).thenReturn(List.of(paid, failed));
        when(refunds.findByPayment_Id(paid.getId())).thenReturn(Optional.of(refund(paid, "20.00", RefundStatus.REFUND_PROCESSED)));

        assertKpiOutputs("100.00");

        assertEquals(new BigDecimal("120.00"), paid.getAmount());
        assertEquals(PaymentStatus.PAID, paid.getPaymentStatus());
        verify(refunds, never()).findByPayment_Id(failed.getId());
        verify(payments, never()).save(any());
        verify(refunds, never()).save(any());
    }

    @Test
    void bothKpiFormatsReportZeroRetainedPaymentAfterAFullRefund() throws Exception {
        Payment paid = payment(10L, PaymentStatus.REFUNDED, "120.00");
        when(payments.findByBooking_Id(booking.getId())).thenReturn(List.of(paid));
        when(refunds.findByPayment_Id(paid.getId())).thenReturn(Optional.of(refund(paid, "120.00", RefundStatus.REFUND_PROCESSED)));

        assertKpiOutputs("0.00");

        assertEquals(new BigDecimal("120.00"), paid.getAmount());
        assertEquals(PaymentStatus.REFUNDED, paid.getPaymentStatus());
    }

    @Test
    void bothKpiFormatsKeepPaymentsUntilTheRefundHasBeenProcessed() throws Exception {
        Payment paid = payment(10L, PaymentStatus.PAID, "120.00");
        when(payments.findByBooking_Id(booking.getId())).thenReturn(List.of(paid));
        when(refunds.findByPayment_Id(paid.getId())).thenReturn(Optional.of(refund(paid, "20.00", RefundStatus.REFUND_PENDING)));

        assertKpiOutputs("120.00");
    }

    private void assertKpiOutputs(String expectedNetPaid) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(reports.generateKpiExcel(List.of(booking))))) {
            assertEquals("Net Paid", workbook.getSheetAt(0).getRow(0).getCell(3).getStringCellValue());
            assertEquals(new BigDecimal(expectedNetPaid).doubleValue(), workbook.getSheetAt(0).getRow(1).getCell(3).getNumericCellValue());
        }
        PdfReader reader = new PdfReader(reports.generateKpiPdf(List.of(booking)));
        try {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertTrue(text.contains("Net Paid"), text);
            assertTrue(text.contains("$" + expectedNetPaid), text);
        } finally {
            reader.close();
        }
    }

    private Payment payment(Long id, PaymentStatus status, String amount) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setBooking(booking);
        payment.setPaymentStatus(status);
        payment.setAmount(new BigDecimal(amount));
        return payment;
    }

    private Refund refund(Payment payment, String amount, RefundStatus status) {
        Refund refund = new Refund();
        refund.setPayment(payment);
        refund.setAmount(new BigDecimal(amount));
        refund.setStatus(status);
        return refund;
    }
}
