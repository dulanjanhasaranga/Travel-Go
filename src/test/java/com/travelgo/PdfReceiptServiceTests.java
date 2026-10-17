package com.travelgo;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.entity.Refund;
import com.travelgo.entity.TourPackage;
import com.travelgo.entity.User;
import com.travelgo.enums.BookingStatus;
import com.travelgo.enums.PaymentStatus;
import com.travelgo.enums.PaymentType;
import com.travelgo.enums.RefundStatus;
import com.travelgo.repository.RefundRepository;
import com.travelgo.service.PdfReceiptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PdfReceiptServiceTests {
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final PdfReceiptService receipts = new PdfReceiptService(refunds);
    private Booking booking;

    @BeforeEach
    void setUp() {
        User owner = new User();
        owner.setId(1L);
        owner.setName("Receipt Customer");
        TourPackage tour = new TourPackage();
        tour.setName("Receipt Tour");
        booking = new Booking();
        booking.setId(10L);
        booking.setUser(owner);
        booking.setTourPackage(tour);
        booking.setBookingStatus(BookingStatus.CANCELLED);
        booking.setTravelDate(LocalDate.of(2030, 2, 1));
    }

    @Test
    void unpaidAttemptsCannotCreateAReceipt() {
        List<Payment> unpaid = List.of(payment(1L, PaymentStatus.PENDING), payment(2L, PaymentStatus.FAILED), payment(3L, PaymentStatus.EXPIRED));
        assertFalse(receipts.hasReceiptablePayment(unpaid));
        assertThrows(IllegalStateException.class, () -> receipts.generateReceipt(booking, unpaid));
        assertThrows(IllegalStateException.class, () -> receipts.generateReceipt(booking, List.of()));
        verifyNoInteractions(refunds);
    }

    @Test void simulatedReceiptClearlyIdentifiesThatNoRealMoneyWasCollected() throws Exception {
        String text=receiptText(List.of(payment(1L,PaymentStatus.PAID)));
        assertTrue(text.contains("SIMULATED PAYMENT RECORD"),text);
        assertTrue(text.contains("No real money was collected"),text);
        assertFalse(text.contains("OFFICIAL INVOICE"),text);
    }

    @Test void suppliedLogoIsEmbeddedAtReceiptResolutionRatherThanItsOriginalPixelSize() throws Exception {
        byte[] preview = receipts.generateReceipt(booking,List.of(payment(1L,PaymentStatus.PAID)));
        if (Boolean.getBoolean("travelgo.audit.export-brand")) {
            var directory = java.nio.file.Path.of("target", "brand-followup-20261013");
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.write(directory.resolve("receipt-preview.pdf"), preview);
        }
        PdfReader reader=new PdfReader(preview);
        try {
            var images=reader.getPageN(1).getAsDict(com.lowagie.text.pdf.PdfName.RESOURCES)
                .getAsDict(com.lowagie.text.pdf.PdfName.XOBJECT);
            assertNotNull(images,"The official logo must remain on the receipt.");
            int count=0;
            int transparentLogos=0;
            for(var name:images.getKeys()) {
                var object=PdfReader.getPdfObject(images.get(name));
                if(object instanceof com.lowagie.text.pdf.PdfDictionary image
                        &&com.lowagie.text.pdf.PdfName.IMAGE.equals(image.getAsName(com.lowagie.text.pdf.PdfName.SUBTYPE))) {
                    count++;
                    assertTrue(image.getAsNumber(com.lowagie.text.pdf.PdfName.WIDTH).intValue()<=240);
                    assertTrue(image.getAsNumber(com.lowagie.text.pdf.PdfName.HEIGHT).intValue()<=240);
                    // The grayscale alpha mask is also listed as an image; it has no mask of its own.
                    if(image.get(com.lowagie.text.pdf.PdfName.SMASK)!=null) transparentLogos++;
                }
            }
            assertTrue(count>0,"The receipt should include its logo image.");
            assertEquals(1,transparentLogos,"The transparent logo must retain its alpha mask.");
        } finally {reader.close();}
    }

    @Test void receiptUsesStoredAgencyContactAndInvoiceUrlUsesSameValidatedFormat() throws Exception {
        var settings=mock(com.travelgo.service.SystemSettingsService.class);var agency=new com.travelgo.entity.SystemSettings();
        agency.setCompanyName("Traverl Go");agency.setCompanyEmail("info@travelgo.com");agency.setCompanyPhone("0763233204");agency.setCompanyAddress("175 Main Street, 11, Colombo, Sri Lanka");
        when(settings.getSettings()).thenReturn(agency);org.springframework.test.util.ReflectionTestUtils.setField(receipts,"settings",settings);
        var bytes=new com.travelgo.service.InvoicePdfGenerator(receipts).generateInvoice(booking,payment(1L,PaymentStatus.PAID));
        PdfReader reader=new PdfReader(bytes);String text=new PdfTextExtractor(reader).getTextFromPage(1);reader.close();
        assertTrue(text.contains("Traverl Go"),text);assertTrue(text.contains("info@travelgo.com"),text);assertTrue(text.contains("0763233204"),text);
        assertFalse(text.contains("support@travelgo.com"),text);assertTrue(text.contains("SIMULATED PAYMENT RECORD"),text);
    }

    @Test
    void successfulStatusWithoutAPaymentDateIsNotReceiptable() {
        Payment payment = payment(1L, PaymentStatus.PAID);
        payment.setPaidAt(null);
        assertFalse(receipts.hasReceiptablePayment(List.of(payment)));
        assertThrows(IllegalStateException.class, () -> receipts.generateReceipt(booking, List.of(payment)));
        verifyNoInteractions(refunds);
    }

    @Test
    void partialRefundReceiptPreservesOriginalAmountAndDisclosesNetPaid() throws Exception {
        Payment paid = payment(1L, PaymentStatus.PAID);
        Payment failed = payment(2L, PaymentStatus.FAILED);
        when(refunds.findByPayment_Id(paid.getId())).thenReturn(Optional.of(refund(paid, "20.00", RefundStatus.REFUND_PROCESSED)));

        String text = receiptText(List.of(paid, failed));
        assertFigure(text, "Original Payments:", "120.00");
        assertFigure(text, "Processed Refunds:", "20.00");
        assertFigure(text, "Net Paid:", "100.00");
        assertTrue(text.contains("Transaction Reference: TXN-1"), text);
        assertEquals(new BigDecimal("120.00"), paid.getAmount());
        assertEquals(PaymentStatus.PAID, paid.getPaymentStatus());
        verify(refunds, never()).findByPayment_Id(failed.getId());
        verify(refunds, never()).save(any());
    }

    @Test
    void fullRefundStillHasAReceiptWithZeroNetPaid() throws Exception {
        Payment paid = payment(1L, PaymentStatus.REFUNDED);
        when(refunds.findByPayment_Id(paid.getId())).thenReturn(Optional.of(refund(paid, "120.00", RefundStatus.REFUND_PROCESSED)));

        assertTrue(receipts.hasReceiptablePayment(List.of(paid)));
        String text = receiptText(List.of(paid));
        assertFigure(text, "Original Payments:", "120.00");
        assertFigure(text, "Processed Refunds:", "120.00");
        assertFigure(text, "Net Paid:", "0.00");
        assertEquals(new BigDecimal("120.00"), paid.getAmount());
        assertEquals(PaymentStatus.REFUNDED, paid.getPaymentStatus());
    }

    @Test
    void pendingRefundIsDisclosedWithoutReducingNetPaid() throws Exception {
        Payment paid = payment(1L, PaymentStatus.PAID);
        when(refunds.findByPayment_Id(paid.getId())).thenReturn(Optional.of(refund(paid, "20.00", RefundStatus.REFUND_PENDING)));

        String text = receiptText(List.of(paid));
        assertFigure(text, "Refund Pending:", "20.00");
        assertFigure(text, "Processed Refunds:", "0.00");
        assertFigure(text, "Net Paid:", "120.00");
    }

    @Test
    void ambiguousFullRefundNeedsReconciliationInsteadOfAnIncorrectReceipt() {
        Payment paid = payment(1L, PaymentStatus.REFUNDED);
        when(refunds.findByPayment_Id(paid.getId())).thenReturn(Optional.empty());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> receipts.generateReceipt(booking, List.of(paid)));
        assertTrue(error.getMessage().contains("payment details need a review"));

        when(refunds.findByPayment_Id(paid.getId())).thenReturn(Optional.of(refund(paid, "20.00", RefundStatus.REFUND_PROCESSED)));
        assertThrows(IllegalStateException.class, () -> receipts.generateReceipt(booking, List.of(paid)));
        assertEquals(PaymentStatus.REFUNDED, paid.getPaymentStatus());
        assertEquals(new BigDecimal("120.00"), paid.getAmount());
    }

    @Test
    void paymentsFromAnotherBookingCannotAppearOnTheReceipt() {
        Payment paid = payment(1L, PaymentStatus.PAID);
        Booking anotherBooking = new Booking();
        anotherBooking.setId(20L);
        paid.setBooking(anotherBooking);

        assertThrows(IllegalArgumentException.class, () -> receipts.generateReceipt(booking, List.of(paid)));
        verifyNoInteractions(refunds);
    }

    private String receiptText(List<Payment> payments) throws Exception {
        PdfReader reader = new PdfReader(receipts.generateReceipt(booking, payments));
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        } finally {
            reader.close();
        }
    }

    private void assertFigure(String text, String label, String amount) {
        // PDF table columns may be adjacent or separated by whitespace in extracted text.
        assertTrue(text.replaceAll("\\s+", "").replace(",", "").contains(label.replaceAll("\\s+", "") + "$" + amount), text);
    }

    private Payment payment(Long id, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setBooking(booking);
        payment.setPaymentStatus(status);
        payment.setPaymentType(PaymentType.VISA_DOCUMENTATION);
        payment.setAmount(new BigDecimal("120.00"));
        payment.setPaidAt(status == PaymentStatus.PAID || status == PaymentStatus.REFUNDED ? LocalDateTime.of(2030, 1, 1, 10, 0) : null);
        payment.setTransactionReference("TXN-" + id);
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
