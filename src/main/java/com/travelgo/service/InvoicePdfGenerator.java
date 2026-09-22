package com.travelgo.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

@Service
public class InvoicePdfGenerator {

    public byte[] generateInvoice(Booking booking, Payment payment) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4);
            PdfWriter.getInstance(document, out);
            document.open();

            // Fonts
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, java.awt.Color.BLACK);
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, java.awt.Color.DARK_GRAY);
            Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 10, java.awt.Color.BLACK);

            // Title
            Paragraph title = new Paragraph("TRAVELGO OFFICIAL INVOICE", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            // Invoice Details
            PdfPTable infoTable = new PdfPTable(2);
            infoTable.setWidthPercentage(100);
            infoTable.setSpacingAfter(20);

            infoTable.addCell(getCell("Invoice No: INV-" + payment.getId(), normalFont));
            infoTable.addCell(getCell("Date: " + payment.getPaidAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), normalFont));
            infoTable.addCell(getCell("Transaction Ref: " + payment.getTransactionReference(), normalFont));
            infoTable.addCell(getCell("Customer: " + booking.getUser().getName(), normalFont));

            document.add(infoTable);

            // Booking Summary Table
            PdfPTable table = new PdfPTable(2);
            table.setWidthPercentage(100);
            table.setSpacingBefore(10);
            table.setSpacingAfter(20);

            PdfPCell header1 = new PdfPCell(new Phrase("Description", headerFont));
            header1.setBackgroundColor(java.awt.Color.LIGHT_GRAY);
            header1.setPadding(8);
            
            PdfPCell header2 = new PdfPCell(new Phrase("Amount", headerFont));
            header2.setBackgroundColor(java.awt.Color.LIGHT_GRAY);
            header2.setPadding(8);

            table.addCell(header1);
            table.addCell(header2);

            String description = payment.getPaymentType().name().replace("_", " ") + " for Booking BKG-" + booking.getId();
            
            PdfPCell cell1 = new PdfPCell(new Phrase(description, normalFont));
            cell1.setPadding(8);
            
            PdfPCell cell2 = new PdfPCell(new Phrase("$" + payment.getAmount().toString(), normalFont));
            cell2.setPadding(8);

            table.addCell(cell1);
            table.addCell(cell2);

            document.add(table);

            // Footer
            Paragraph footer = new Paragraph("Thank you for choosing TravelGO! If you have any questions, please contact support@travelgo.com", normalFont);
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return new byte[0];
        }
    }

    private PdfPCell getCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(5);
        return cell;
    }
}
