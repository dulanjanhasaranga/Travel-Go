package com.travelgo.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

@Service
public class ReportService {

    public byte[] generateBookingsExcel(List<Booking> bookings) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Bookings");

            CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row headerRow = sheet.createRow(0);
            String[] headers = {"Ref ID", "Customer Name", "Customer Email", "Tour Package", "Travel Date", "Travelers", "Status", "Amount"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (Booking b : bookings) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue("BK-" + b.getId());
                row.createCell(1).setCellValue(b.getUser() != null ? b.getUser().getName() : "");
                row.createCell(2).setCellValue(b.getUser() != null ? b.getUser().getEmail() : "");
                row.createCell(3).setCellValue(b.getTourPackage() != null ? b.getTourPackage().getName() : "");
                row.createCell(4).setCellValue(b.getTravelDate() != null ? b.getTravelDate().toString() : "");
                row.createCell(5).setCellValue(b.getNumberOfTravelers());
                row.createCell(6).setCellValue(b.getBookingStatus().name());
                row.createCell(7).setCellValue(b.getTotalPackageAmount() != null ? b.getTotalPackageAmount().doubleValue() : 0.0);
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Excel report", e);
        }
    }

    public byte[] generateBookingsPdf(List<Booking> bookings) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD);
            Paragraph title = new Paragraph("Active Bookings Report", titleFont);
            title.setAlignment(Paragraph.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{1.5f, 2f, 3f, 2f, 1f, 2f, 2f});

            Font tableHeaderFont = new Font(Font.HELVETICA, 12, Font.BOLD);
            String[] headers = {"Ref ID", "Customer", "Tour Package", "Date", "Pax", "Status", "Amount"};
            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(h, tableHeaderFont));
                cell.setPadding(5);
                table.addCell(cell);
            }

            Font rowFont = new Font(Font.HELVETICA, 10, Font.NORMAL);
            for (Booking b : bookings) {
                table.addCell(new Phrase("BK-" + b.getId(), rowFont));
                table.addCell(new Phrase(b.getUser() != null ? b.getUser().getName() : "", rowFont));
                table.addCell(new Phrase(b.getTourPackage() != null ? b.getTourPackage().getName() : "", rowFont));
                table.addCell(new Phrase(b.getTravelDate() != null ? b.getTravelDate().toString() : "", rowFont));
                table.addCell(new Phrase(String.valueOf(b.getNumberOfTravelers()), rowFont));
                table.addCell(new Phrase(b.getBookingStatus().name(), rowFont));
                table.addCell(new Phrase(b.getTotalPackageAmount() != null ? "$" + b.getTotalPackageAmount().toString() : "$0.0", rowFont));
            }

            document.add(table);
            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF report", e);
        }
    }

    public byte[] generatePaymentsExcel(List<Payment> payments) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Payments");

            CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row headerRow = sheet.createRow(0);
            String[] headers = {"TXN ID", "Date", "Amount", "Method", "Type", "Status", "Booking Ref"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (Payment p : payments) {
                Row row = sheet.createRow(rowIdx++);
                String txnRef = p.getTransactionReference() != null ? p.getTransactionReference() : "TXN-" + p.getId();
                row.createCell(0).setCellValue(txnRef);
                row.createCell(1).setCellValue(p.getPaidAt() != null ? p.getPaidAt().toString() : (p.getCreatedAt() != null ? p.getCreatedAt().toString() : ""));
                row.createCell(2).setCellValue(p.getAmount() != null ? p.getAmount().doubleValue() : 0.0);
                row.createCell(3).setCellValue(p.getPaymentMethod() != null ? p.getPaymentMethod() : "");
                row.createCell(4).setCellValue(p.getPaymentType() != null ? p.getPaymentType().name() : "");
                row.createCell(5).setCellValue(p.getPaymentStatus() != null ? p.getPaymentStatus().name() : "");
                row.createCell(6).setCellValue(p.getBooking() != null ? "BK-" + p.getBooking().getId() : "");
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Excel report", e);
        }
    }

    public byte[] generatePaymentsPdf(List<Payment> payments) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD);
            Paragraph title = new Paragraph("Financial Ledger Report", titleFont);
            title.setAlignment(Paragraph.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            PdfPTable table = new PdfPTable(6);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{2f, 2f, 2f, 2f, 2f, 2f});

            Font tableHeaderFont = new Font(Font.HELVETICA, 12, Font.BOLD);
            String[] headers = {"TXN ID", "Amount", "Method", "Type", "Status", "Booking Ref"};
            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(h, tableHeaderFont));
                cell.setPadding(5);
                table.addCell(cell);
            }

            Font rowFont = new Font(Font.HELVETICA, 10, Font.NORMAL);
            for (Payment p : payments) {
                table.addCell(new Phrase(p.getTransactionReference() != null ? p.getTransactionReference() : "TXN-" + p.getId(), rowFont));
                table.addCell(new Phrase(p.getAmount() != null ? "$" + p.getAmount().toString() : "$0.0", rowFont));
                table.addCell(new Phrase(p.getPaymentMethod() != null ? p.getPaymentMethod() : "", rowFont));
                table.addCell(new Phrase(p.getPaymentType() != null ? p.getPaymentType().name() : "", rowFont));
                table.addCell(new Phrase(p.getPaymentStatus() != null ? p.getPaymentStatus().name() : "", rowFont));
                table.addCell(new Phrase(p.getBooking() != null ? "BK-" + p.getBooking().getId() : "", rowFont));
            }

            document.add(table);
            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF report", e);
        }
    }
}
