package com.travelgo.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Chunk;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.entity.Refund;
import com.travelgo.enums.PaymentStatus;
import com.travelgo.enums.RefundStatus;
import com.travelgo.repository.RefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.awt.Color;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class PdfReceiptService {
    private static final DateTimeFormatter PAYMENT_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter TRAVEL_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final int RECEIPT_LOGO_PIXELS = 240;
    private static final Color BLUE = new Color(35, 61, 112);
    private static final Color INK = new Color(36, 50, 71);
    private static final Color MUTED = new Color(82, 100, 127);
    private static final Color PALE = new Color(244, 247, 252);
    private static final Color LINE = new Color(221, 228, 239);
    private final RefundRepository refunds;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private SystemSettingsService settings;
    @org.springframework.beans.factory.annotation.Value("${travelgo.payments.simulated:true}") private boolean simulated=true;

    public PdfReceiptService(RefundRepository refunds) {
        this.refunds = refunds;
    }

    public boolean hasReceiptablePayment(List<Payment> payments) {
        return payments.stream().anyMatch(this::isReceiptablePayment);
    }

    private boolean isReceiptablePayment(Payment payment) {
        return (payment.getPaymentStatus() == PaymentStatus.PAID || payment.getPaymentStatus() == PaymentStatus.REFUNDED)
                && payment.getPaidAt() != null;
    }

    @Transactional(readOnly = true)
    public byte[] generateReceipt(Booking booking, List<Payment> payments) {
        if (!hasReceiptablePayment(payments)) {
            throw new IllegalStateException("A receipt is available after a successful payment.");
        }

        List<ReceiptPayment> successfulPayments = new ArrayList<>();
        for (Payment payment : payments) {
            if (!isReceiptablePayment(payment)) continue;
            if (payment.getBooking() == null || !booking.getId().equals(payment.getBooking().getId())) {
                throw new IllegalArgumentException("The payment does not belong to this booking.");
            }
            if (payment.getAmount() == null || payment.getAmount().signum() <= 0) {
                throw reconciliationRequired();
            }
            Refund refund = refunds.findByPayment_Id(payment.getId()).orElse(null);
            BigDecimal processedRefund = refund != null && refund.getStatus() == RefundStatus.REFUND_PROCESSED
                    ? refund.getAmount() : BigDecimal.ZERO;
            if (processedRefund == null || processedRefund.signum() < 0 || processedRefund.compareTo(payment.getAmount()) > 0
                    || (payment.getPaymentStatus() == PaymentStatus.REFUNDED && processedRefund.compareTo(payment.getAmount()) != 0)) {
                throw reconciliationRequired();
            }
            successfulPayments.add(new ReceiptPayment(payment, refund, processedRefund));
        }
        // Stable presentation order only: no historical payment or refund record is changed.
        successfulPayments.sort(Comparator.comparing((ReceiptPayment row) -> row.payment().getPaidAt())
                .thenComparing(row -> row.payment().getId(), Comparator.nullsLast(Comparator.naturalOrder())));

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 44, 44, 44, 76);
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.setPageEvent(new ReceiptFooter(simulated));
            var agency=settings==null?null:settings.getSettings();
            String agencyName=agency!=null&&agency.getCompanyName()!=null&&!agency.getCompanyName().isBlank()?agency.getCompanyName():"TravelGO";
            document.addTitle(agencyName + " - Payment receipt");
            document.addAuthor(agencyName);
            document.addSubject("Payment record for booking " + booking.getId());
            document.open();
            addHeader(document, booking, successfulPayments.get(successfulPayments.size()-1), agencyName, agency);
            addBookingDetails(document, booking);
            section(document, "Payment history", "Successful payments recorded for this booking. All amounts are in USD.");
            PdfPTable history = table(3, 2.6f, 1.15f, 1f);
            history.setHeaderRows(1);
            // Move a complete payment to the next page instead of separating its reference from its amount.
            // OpenPDF can still split a genuinely oversized row when it cannot fit on a fresh page.
            history.setSplitLate(true);
            history.addCell(columnHeading("Payment", Element.ALIGN_LEFT));
            history.addCell(columnHeading("Paid on", Element.ALIGN_LEFT));
            history.addCell(columnHeading("Amount", Element.ALIGN_RIGHT));
            BigDecimal totalPaid = BigDecimal.ZERO;
            BigDecimal totalRefunded = BigDecimal.ZERO;
            int rowIndex = 0;
            for (ReceiptPayment item : successfulPayments) {
                Payment payment = item.payment();
                Color background = rowIndex++ % 2 == 0 ? Color.WHITE : PALE;
                PdfPCell description = bodyCell(background);
                description.addElement(text(label(payment.getPaymentType().name()), font(10, true, INK), 14));
                if (payment.getId() != null) description.addElement(text("Payment #" + payment.getId(), font(8.5f, false, MUTED), 12));
                if (payment.getTransactionReference() != null && !payment.getTransactionReference().isBlank()) {
                    description.addElement(text("Transaction Reference: " + payment.getTransactionReference(), font(8.5f, false, MUTED), 12));
                }
                if (item.processedRefund().signum() > 0) {
                    description.addElement(text("Processed Refund: $" + money(item.processedRefund()), font(8.5f, false, MUTED), 12));
                } else if (item.refund() != null && item.refund().getStatus() == RefundStatus.REFUND_PENDING) {
                    description.addElement(text("Refund Pending: $" + money(item.refund().getAmount()), font(8.5f, false, MUTED), 12));
                }
                history.addCell(description);
                PdfPCell date = bodyCell(background);
                date.addElement(text(payment.getPaidAt().format(PAYMENT_DATE), font(9, false, INK), 14));
                history.addCell(date);
                PdfPCell amount = bodyCell(background);
                Paragraph amountText = text("$" + money(payment.getAmount()), font(10, true, INK), 14);
                amountText.setAlignment(Element.ALIGN_RIGHT);
                amount.addElement(amountText);
                history.addCell(amount);
                totalPaid = totalPaid.add(payment.getAmount());
                totalRefunded = totalRefunded.add(item.processedRefund());
            }
            document.add(history);
            addSummary(document, totalPaid, totalRefunded);
            document.close();
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("The payment receipt could not be generated. Try again later.", e);
        }
    }

    private void addHeader(Document document, Booking booking, ReceiptPayment latest, String agencyName,
            com.travelgo.entity.SystemSettings agency) throws Exception {
        PdfPTable header = table(2, 1f, 1.6f);
        PdfPCell brand = plainCell();
        brand.setFixedHeight(68);
        var resource = new org.springframework.core.io.ClassPathResource("static/images/brand/travelgo-airborne.png");
        if (resource.exists()) {
            var mark = receiptLogo(resource);
            // Clip only the transparent canvas in PDF coordinates, exactly as the website fits the PNG.
            // The supplied artwork and its bounded decoded pixels remain unchanged.
            brand.setCellEvent((cell, bounds, canvases) -> {
                float width = 112, height = width * 618 / 1007;
                float x = bounds.getLeft(), y = bounds.getTop() - height;
                var canvas = canvases[PdfPTable.TEXTCANVAS];
                canvas.saveState();
                try {
                    canvas.rectangle(x, y, width, height); canvas.clip(); canvas.newPath();
                    float full = width * 1254 / 1007;
                    canvas.addImage(mark, full, 0, 0, full, x - width * 126 / 1007, y - width * 289 / 1007);
                } catch (com.lowagie.text.DocumentException unavailable) {
                    throw new IllegalStateException("The receipt logo could not be placed.", unavailable);
                } finally { canvas.restoreState(); }
            });
        } else brand.addElement(text(agencyName, font(18, true, BLUE), 23));
        header.addCell(brand);
        PdfPCell heading = plainCell();
        Paragraph title = text("Payment receipt", font(25, true, BLUE), 29);
        title.setAlignment(Element.ALIGN_RIGHT); heading.addElement(title);
        Paragraph reference = text("Booking Reference: #" + booking.getId(), font(10, true, INK), 18);
        reference.setAlignment(Element.ALIGN_RIGHT); heading.addElement(reference);
        Paragraph date = text("Latest payment: " + latest.payment().getPaidAt().format(PAYMENT_DATE), font(8.5f, false, MUTED), 14);
        date.setAlignment(Element.ALIGN_RIGHT); heading.addElement(date);
        header.addCell(heading);
        document.add(header);
        Paragraph company = text(agencyName, font(11, true, INK), 16);
        document.add(company);
        if (agency != null) {
            List<String> contact = new ArrayList<>();
            if (!safe(agency.getCompanyEmail()).isBlank()) contact.add(agency.getCompanyEmail());
            if (!safe(agency.getCompanyPhone()).isBlank()) contact.add(agency.getCompanyPhone());
            if (!contact.isEmpty()) document.add(text(String.join("  |  ", contact), font(9, false, MUTED), 13));
            if (!safe(agency.getCompanyAddress()).isBlank()) document.add(text(agency.getCompanyAddress(), font(9, false, MUTED), 13));
        }
        if (simulated) {
            PdfPTable notice = table(1, 1f);
            notice.setSpacingBefore(18);
            PdfPCell cell = bodyCell(PALE);
            cell.setBorder(Rectangle.LEFT); cell.setBorderColor(BLUE); cell.setBorderWidth(3);
            cell.addElement(text("SIMULATED PAYMENT RECORD", font(9, true, BLUE), 14));
            cell.addElement(text("No real money was collected by this website.", font(9, false, INK), 13));
            notice.addCell(cell); document.add(notice);
        }
    }

    private void addBookingDetails(Document document, Booking booking) throws Exception {
        section(document, "Booking details", null);
        PdfPTable details = table(2, 1f, 1f);
        details.setKeepTogether(true);
        PdfPCell customer = bodyCell(PALE);
        customer.setBorder(Rectangle.NO_BORDER);
        customer.addElement(text("RECEIVED FROM", font(8, true, MUTED), 14));
        customer.addElement(text(safe(booking.getUser().getName()), font(12, true, INK), 17));
        customer.addElement(text("Booking status: " + label(booking.getBookingStatus().name()), font(9, false, MUTED), 17));
        details.addCell(customer);
        PdfPCell journey = bodyCell(PALE);
        journey.setBorder(Rectangle.NO_BORDER);
        journey.addElement(text("JOURNEY", font(8, true, MUTED), 14));
        journey.addElement(text(safe(booking.getTourPackage().getName()), font(12, true, INK), 17));
        journey.addElement(text("Travel date: " + booking.getTravelDate().format(TRAVEL_DATE), font(9, false, MUTED), 17));
        if (booking.getNumberOfTravelers() != null) journey.addElement(text("Travelers: " + booking.getNumberOfTravelers(), font(9, false, MUTED), 14));
        details.addCell(journey);
        document.add(details);
    }

    private void addSummary(Document document, BigDecimal paid, BigDecimal refunded) throws Exception {
        PdfPTable summary = table(2, 1.5f, 1.7f);
        summary.setSpacingBefore(18);
        summary.setKeepTogether(true);
        PdfPCell note = plainCell(); note.setPaddingRight(24);
        note.addElement(text("Your payment record", font(10, true, BLUE), 16));
        note.addElement(text("View your booking details for the current visa status, supplier confirmation and any pending refunds.", font(9, false, MUTED), 14));
        note.addElement(text("Payment dates are shown as recorded. Currency: USD.", font(8, false, MUTED), 13));
        summary.addCell(note);
        PdfPTable totals = table(2, 1.25f, 1f);
        PdfPCell currency = plainCell(); currency.setColspan(2); currency.setPaddingBottom(10);
        currency.addElement(text("PAYMENT SUMMARY / USD", font(8, true, BLUE), 12)); totals.addCell(currency);
        totalRow(totals, "Original Payments:", paid, false);
        totalRow(totals, "Processed Refunds:", refunded, false);
        totalRow(totals, "Net Paid:", paid.subtract(refunded), true);
        PdfPCell totalCell = plainCell(); totalCell.addElement(totals); summary.addCell(totalCell);
        document.add(summary);
    }

    private static void totalRow(PdfPTable table, String label, BigDecimal amount, boolean emphasis) {
        Color background = emphasis ? BLUE : PALE;
        PdfPCell name = bodyCell(background); name.setBorder(Rectangle.NO_BORDER); name.setPadding(9);
        name.addElement(text(label, font(emphasis ? 11 : 9, emphasis, emphasis ? Color.WHITE : INK), 17));
        table.addCell(name);
        PdfPCell value = bodyCell(background); value.setBorder(Rectangle.NO_BORDER); value.setPadding(9);
        String formatted = "$" + money(amount);
        Font amountFont = font(emphasis ? 15 : 10, true, emphasis ? Color.WHITE : INK);
        float available = (PageSize.A4.getWidth() - 88) * 1.7f / 3.2f / 2.25f - 22;
        float measured = amountFont.getCalculatedBaseFont(false).getWidthPoint(formatted, amountFont.getSize());
        if (measured > available) amountFont.setSize(Math.max(8, amountFont.getSize() * available / measured));
        Paragraph amountText = text(formatted, amountFont, 17); amountText.setAlignment(Element.ALIGN_RIGHT);
        value.addElement(amountText); table.addCell(value);
    }

    private static void section(Document document, String heading, String description) throws Exception {
        Paragraph title = text(heading, font(12, true, BLUE), 17);
        title.setSpacingBefore(18); title.setSpacingAfter(description == null ? 10 : 4); document.add(title);
        if (description != null) {
            Paragraph help = text(description, font(8.5f, false, MUTED), 13);
            help.setSpacingAfter(10); document.add(help);
        }
    }

    private static PdfPTable table(int columns, float... widths) throws com.lowagie.text.DocumentException {
        PdfPTable table = new PdfPTable(columns); table.setWidthPercentage(100); table.setWidths(widths); return table;
    }

    private static PdfPCell plainCell() {
        PdfPCell cell = new PdfPCell(); cell.setBorder(Rectangle.NO_BORDER); cell.setPadding(0); return cell;
    }

    private static PdfPCell bodyCell(Color background) {
        PdfPCell cell = plainCell(); cell.setPadding(11); cell.setBackgroundColor(background);
        cell.setBorder(Rectangle.BOTTOM); cell.setBorderWidth(.5f); cell.setBorderColor(LINE);
        cell.setVerticalAlignment(Element.ALIGN_TOP); return cell;
    }

    private static PdfPCell columnHeading(String label, int alignment) {
        PdfPCell cell = bodyCell(BLUE); cell.setPaddingTop(9); cell.setPaddingBottom(9);
        Paragraph text = text(label, font(9, true, Color.WHITE), 13); text.setAlignment(alignment);
        cell.addElement(text); return cell;
    }

    private static Font font(float size, boolean bold, Color color) {
        return FontFactory.getFont(bold ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, size, color);
    }

    private static Paragraph text(String value, Font font, float leading) {
        Paragraph paragraph = new Paragraph();
        var tokens = java.util.regex.Pattern.compile("\\S{24,}").matcher(value);
        int copied = 0;
        while (tokens.find()) {
            paragraph.add(new Chunk(value.substring(copied, tokens.start()), font));
            Chunk token = new Chunk(tokens.group(), font);
            // Wrap unusually long provider references while keeping normal prose at word boundaries.
            token.setSplitCharacter((start, current, end, characters, chunks) -> true);
            paragraph.add(token); copied = tokens.end();
        }
        paragraph.add(new Chunk(value.substring(copied), font));
        paragraph.setLeading(leading); return paragraph;
    }

    private static final class ReceiptFooter extends PdfPageEventHelper {
        private final boolean simulated;
        private ReceiptFooter(boolean simulated) { this.simulated = simulated; }
        @Override public void onEndPage(PdfWriter writer, Document document) {
            var canvas = writer.getDirectContent();
            canvas.saveState(); canvas.setColorStroke(LINE); canvas.setLineWidth(.6f);
            canvas.moveTo(document.left(), 60); canvas.lineTo(document.right(), 60); canvas.stroke(); canvas.restoreState();
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, new Phrase("Payment receipt | USD", font(8, false, MUTED)), document.left(), 43, 0);
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), font(8, false, MUTED)), document.right(), 43, 0);
            if (simulated) ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT,
                    new Phrase("SIMULATED PAYMENT RECORD - No real money was collected by this website.", font(7, true, BLUE)), document.left(), 28, 0);
        }
    }

    private IllegalStateException reconciliationRequired() {
        return new IllegalStateException("Your payment details need a review before we can issue a receipt. Contact your travel consultant.");
    }

    private com.lowagie.text.Image receiptLogo(org.springframework.core.io.Resource resource) throws Exception {
        // Display scaling happens after decoding in OpenPDF. Subsample the source
        // first so a large supplied logo cannot exhaust the checkout JVM's heap.
        try (var stream=resource.getInputStream(); var input=javax.imageio.ImageIO.createImageInputStream(stream)) {
            if(input==null)throw new java.io.IOException("The receipt logo could not be read.");
            var readers=javax.imageio.ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new java.io.IOException("The receipt logo format is unsupported.");
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                int largest=Math.max(reader.getWidth(0),reader.getHeight(0));
                int sample=Math.max(1,(largest+RECEIPT_LOGO_PIXELS-1)/RECEIPT_LOGO_PIXELS);
                var parameters=reader.getDefaultReadParam();
                parameters.setSourceSubsampling(sample,sample,0,0);
                return com.lowagie.text.Image.getInstance(reader.read(0,parameters),null);
            } finally {reader.dispose();}
        }
    }

    private static String money(BigDecimal amount) {
        var format = new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(java.math.RoundingMode.UNNECESSARY);
        return format.format(amount.setScale(2, java.math.RoundingMode.UNNECESSARY));
    }
    private String safe(String value){return value==null?"":value;}
    private String label(String value){String text=value.toLowerCase(java.util.Locale.ROOT).replace('_',' ');return Character.toUpperCase(text.charAt(0))+text.substring(1);}

    private record ReceiptPayment(Payment payment, Refund refund, BigDecimal processedRefund) {}
}
