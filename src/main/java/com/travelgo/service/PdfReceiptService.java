package com.travelgo.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

@Service
public class PdfReceiptService {

    public byte[] generateReceipt(Booking booking, List<Payment> payments) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, baos);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            Font regularFont = FontFactory.getFont(FontFactory.HELVETICA, 12);

            Paragraph title = new Paragraph("TravelGO Payment Receipt", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            document.add(new Paragraph("Booking Reference: " + booking.getId(), headerFont));
            document.add(new Paragraph("Customer: " + booking.getUser().getName(), regularFont));
            document.add(new Paragraph("Package: " + booking.getTourPackage().getName(), regularFont));
            document.add(new Paragraph("Travel Date: " + booking.getTravelDate(), regularFont));
            document.add(new Paragraph("Status: " + booking.getBookingStatus(), regularFont));
            
            document.add(new Paragraph("\nPayments Made:", headerFont));
            BigDecimal totalPaid = BigDecimal.ZERO;
            for (Payment p : payments) {
                if (p.getPaymentStatus() == com.travelgo.enums.PaymentStatus.PAID) {
                    document.add(new Paragraph("- " + p.getPaymentType() + ": $" + p.getAmount() + " on " + p.getPaidAt(), regularFont));
                    totalPaid = totalPaid.add(p.getAmount());
                }
            }
            
            document.add(new Paragraph("\nTotal Paid: $" + totalPaid, headerFont));

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Could not generate PDF receipt", e);
        }
    }
}
