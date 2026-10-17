package com.travelgo;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.entity.Refund;
import com.travelgo.entity.SystemSettings;
import com.travelgo.entity.TourPackage;
import com.travelgo.entity.User;
import com.travelgo.enums.BookingStatus;
import com.travelgo.enums.PaymentStatus;
import com.travelgo.enums.PaymentType;
import com.travelgo.enums.RefundStatus;
import com.travelgo.repository.RefundRepository;
import com.travelgo.service.PdfReceiptService;
import com.travelgo.service.SystemSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the downloadable document with synthetic records; no Spring context or database. */
class ReceiptLayoutTests {
    private final RefundRepository refunds = mock(RefundRepository.class);
    private final PdfReceiptService receipts = new PdfReceiptService(refunds);
    private Booking booking;

    @BeforeEach
    void setUp() {
        User owner = new User();
        owner.setId(101L);
        owner.setName("Alex Morgan - sample customer");
        owner.setEmail("alex@example.test");
        TourPackage tour = new TourPackage();
        tour.setName("Southern Coast Discovery - sample itinerary");
        booking = new Booking();
        booking.setId(8342L);
        booking.setUser(owner);
        booking.setTourPackage(tour);
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        booking.setNumberOfTravelers(2);
        booking.setTravelDate(LocalDate.of(2031, 7, 19));
        booking.setCreatedAt(LocalDateTime.of(2030, 12, 1, 8, 0));
        booking.setTotalPackageAmount(new BigDecimal("1699.50"));
        booking.setPackageUnitPrice(new BigDecimal("849.75"));

        SystemSettings agency = new SystemSettings();
        agency.setCompanyName("TravelGO - sample agency");
        agency.setCompanyEmail("receipts@example.test");
        agency.setCompanyPhone("+94 00 000 0000");
        agency.setCompanyAddress("12 Example Road, Colombo, Sri Lanka - sample address");
        // Historical payment amounts are USD even if the current display preference changes.
        agency.setCurrency("LKR");
        SystemSettingsService settings = mock(SystemSettingsService.class);
        when(settings.getSettings()).thenReturn(agency);
        ReflectionTestUtils.setField(receipts, "settings", settings);
        when(refunds.findByPayment_Id(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void normalReceiptUsesStoredInformationAndClearlyIdentifiesUsd() throws Exception {
        Payment visa = payment(201L, "120.00", LocalDateTime.of(2031, 4, 17, 9, 35),
                PaymentType.VISA_DOCUMENTATION, "SAMPLE-VISA-201");
        Payment packagePayment = payment(202L, "1699.50", LocalDateTime.of(2031, 4, 18, 14, 20),
                PaymentType.FULL_PACKAGE, "SAMPLE-PACKAGE-202");
        Refund refund = new Refund();
        refund.setPayment(visa);
        refund.setAmount(new BigDecimal("20.00"));
        refund.setStatus(RefundStatus.REFUND_PROCESSED);
        when(refunds.findByPayment_Id(visa.getId())).thenReturn(Optional.of(refund));

        byte[] bytes = receipts.generateReceipt(booking, List.of(packagePayment, visa));
        export("normal-simulated.pdf", bytes);
        List<String> pages = pageTexts(bytes);
        assertEquals(1, pages.size(), "A routine two-payment receipt should fit on one page.");
        String text = String.join("\n", pages);
        assertTrue(text.toLowerCase(Locale.ROOT).contains("payment receipt"), text);
        assertTrue(text.contains("TravelGO - sample agency"), text);
        assertTrue(text.contains("receipts@example.test"), text);
        assertTrue(text.contains("+94 00 000 0000"), text);
        assertTrue(text.contains("8342"), "The reference must use the stored booking ID.");
        assertTrue(compact(text).contains(compact(booking.getUser().getName())), text);
        assertTrue(compact(text).contains(compact(booking.getTourPackage().getName())), text);
        assertTrue(text.contains("USD"), "Amounts must identify their currency explicitly.");
        assertFalse(text.contains("LKR"), "A settings preference must not relabel stored USD payments.");
        assertStoredDate(text, "2031-04-17", "17 Apr 2031", "17 April 2031");
        assertTrue(text.contains("09:35"), "The payment time must come from the stored paidAt value.");
        assertStoredDate(text, "2031-07-19", "19 Jul 2031", "19 July 2031");
        assertContainsAmount(text, new BigDecimal("1819.50"));
        assertContainsAmount(text, new BigDecimal("20.00"));
        assertContainsAmount(text, new BigDecimal("1799.50"));
        assertFalse(text.toLowerCase(Locale.ROOT).contains("balance due"),
                "Payment totals alone do not establish a booking balance.");
        assertNoInventedFiscalIdentity(text);
        assertPageNumber(pages.get(0), 1);
        assertTrue(pages.get(0).contains("SIMULATED PAYMENT RECORD"), text);
        assertTrue(pages.get(0).contains("No real money was collected"), text);
        assertEquals(new BigDecimal("120.00"), visa.getAmount());
        assertEquals(new BigDecimal("1699.50"), packagePayment.getAmount());
        verify(refunds, never()).save(any());
    }

    @Test
    void longContentAndManyPaymentsRemainCompleteAcrossNumberedPages() throws Exception {
        String customerName = "Alexandra Morgan " + "Long customer name for layout review ".repeat(5) + "Final customer name";
        String packageName = "Coastal and Cultural Discovery " + "Extended itinerary destination description ".repeat(6) + "Final itinerary destination";
        booking.getUser().setName(customerName);
        booking.getTourPackage().setName(packageName);

        List<Payment> ordered = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int index = 0; index < 52; index++) {
            BigDecimal amount = new BigDecimal("50.25").add(BigDecimal.valueOf(index));
            String reference = String.format(Locale.ROOT, "SAMPLE%03d", index)
                    + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".repeat(4) + "END";
            // Consecutive pairs share the same timestamp, making the ID tie-break observable.
            ordered.add(payment(300L + index, amount.toPlainString(),
                    LocalDateTime.of(2031, 5, 1, 11, 15).plusDays(index / 2),
                    PaymentType.FULL_PACKAGE, reference));
            total = total.add(amount);
        }
        List<Payment> shuffled = new ArrayList<>(ordered);
        Collections.reverse(shuffled);
        byte[] bytes = receipts.generateReceipt(booking, shuffled);
        export("multipage-simulated.pdf", bytes);
        List<String> pages = pageTexts(bytes);
        assertTrue(pages.size() >= 3, "The fixture must exercise payment-table pagination.");
        String allText = String.join("\n", pages);
        String compactText = compact(allText);
        assertTrue(compactText.contains(compact(customerName)), "The complete long customer name must remain readable.");
        assertTrue(compactText.contains(compact(packageName)), "The complete long package name must remain readable.");
        int previousReference = -1;
        for (Payment payment : ordered) {
            int referencePosition = compactText.indexOf(payment.getTransactionReference());
            assertTrue(referencePosition >= 0, "The full wrapped reference must be retained: " + payment.getTransactionReference());
            assertTrue(referencePosition > previousReference, "Payments must be ordered by stored paidAt, then ID.");
            previousReference = referencePosition;
            assertContainsAmount(allText, payment.getAmount());
        }
        assertContainsAmount(pages.get(pages.size() - 1), total);
        assertTrue(pages.get(pages.size() - 1).contains("USD"), "The final totals must identify USD.");
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            String pageText = pages.get(pageIndex);
            assertPageNumber(pageText, pageIndex + 1);
            assertTrue(pageText.contains("SIMULATED PAYMENT RECORD"), "Simulation disclosure missing on page " + (pageIndex + 1));
            assertTrue(pageText.contains("No real money was collected"), "Simulation explanation missing on page " + (pageIndex + 1));
            if (pageText.contains("SAMPLE")) {
                assertTrue(Pattern.compile("\\bPayment\\b").matcher(pageText).find(), "Payment heading missing on page " + (pageIndex + 1));
                assertTrue(pageText.contains("Paid on"), "Paid-on heading missing on page " + (pageIndex + 1));
                assertTrue(pageText.contains("Amount"), "Amount heading missing on page " + (pageIndex + 1));
            }
        }
        assertNoInventedFiscalIdentity(allText);
        verify(refunds, never()).save(any());
    }

    @Test
    void realModeReceiptOmitsSimulationDisclosureAndKeepsStoredReference() throws Exception {
        ReflectionTestUtils.setField(receipts, "simulated", false);
        Payment payment = payment(501L, "320.75", LocalDateTime.of(2031, 6, 2, 16, 45),
                PaymentType.FULL_PACKAGE, "SAMPLE-REAL-MODE-501");
        byte[] bytes = receipts.generateReceipt(booking, List.of(payment));
        export("normal-real.pdf", bytes);
        List<String> pages = pageTexts(bytes);
        String text = String.join("\n", pages);
        assertFalse(text.toLowerCase(Locale.ROOT).contains("simulated"), text);
        assertFalse(text.contains("No real money was collected"), text);
        assertTrue(compact(text).contains(payment.getTransactionReference()), text);
        assertTrue(text.contains("USD"), text);
        assertContainsAmount(text, new BigDecimal("320.75"));
        assertStoredDate(text, "2031-06-02", "2 Jun 2031", "02 Jun 2031", "2 June 2031", "02 June 2031");
        assertTrue(text.contains("16:45"), text);
        assertNoInventedFiscalIdentity(text);
        for (int index = 0; index < pages.size(); index++) assertPageNumber(pages.get(index), index + 1);
        verify(refunds, never()).save(any());
    }

    @Test
    void largeStoredAmountsKeepTheirCentsAndGroupedTotals() throws Exception {
        List<Payment> payments = new ArrayList<>();
        for (int index = 0; index < 4; index++) payments.add(payment(600L + index, "99999999.99",
                LocalDateTime.of(2031, 6, 2, 16, 45).plusDays(index), PaymentType.FULL_PACKAGE, "SAMPLE-LARGE-" + index));
        byte[] bytes = receipts.generateReceipt(booking, payments);
        export("large-amounts.pdf", bytes);
        String text = String.join("\n", pageTexts(bytes));
        assertTrue(text.contains("99,999,999.99"), text);
        assertTrue(text.contains("399,999,999.96"), text);
        assertContainsAmount(text, new BigDecimal("399999999.96"));
        for (Payment payment : payments) assertEquals(new BigDecimal("99999999.99"), payment.getAmount());
        verify(refunds, never()).save(any());
    }

    private Payment payment(Long id, String amount, LocalDateTime paidAt, PaymentType type, String reference) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setBooking(booking);
        payment.setPaymentType(type);
        payment.setPaymentStatus(PaymentStatus.PAID);
        payment.setAmount(new BigDecimal(amount));
        payment.setPaidAt(paidAt);
        payment.setTransactionReference(reference);
        payment.setPaymentMethod("Sample payment record");
        return payment;
    }

    private List<String> pageTexts(byte[] bytes) throws Exception {
        PdfReader reader = new PdfReader(bytes);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            List<String> texts = new ArrayList<>();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) texts.add(extractor.getTextFromPage(page));
            return texts;
        } finally {
            reader.close();
        }
    }

    private void assertPageNumber(String text, int pageNumber) {
        assertTrue(Pattern.compile("Page\\s+" + pageNumber + "\\b").matcher(text).find(),
                "Missing Page " + pageNumber + " footer: " + text);
    }

    private void assertStoredDate(String text, String... acceptedFormats) {
        assertTrue(java.util.Arrays.stream(acceptedFormats).anyMatch(text::contains),
                "The stored date must be displayed: " + String.join(" / ", acceptedFormats) + "\n" + text);
    }

    private void assertContainsAmount(String text, BigDecimal amount) {
        String value = amount.setScale(2).toPlainString();
        assertTrue(compact(text).replace(",", "").contains(value), "Missing exact stored amount " + value + "\n" + text);
    }

    private void assertNoInventedFiscalIdentity(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String unsupportedLabel : List.of("official invoice", "tax invoice", "vat number", "tax registration", "invoice number", "invoice no.", "receipt number", "receipt no.")) {
            assertFalse(lower.contains(unsupportedLabel), "Do not fabricate official fiscal identity: " + unsupportedLabel);
        }
    }

    private String compact(String value) {
        return value.replaceAll("\\s+", "");
    }

    private void export(String filename, byte[] bytes) throws Exception {
        if (!Boolean.getBoolean("travelgo.audit.export-receipt")) return;
        Path directory = Path.of("target", "receipt-redesign-20261013");
        Files.createDirectories(directory);
        Files.write(directory.resolve(filename), bytes);
    }
}
