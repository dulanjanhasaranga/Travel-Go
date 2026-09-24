package com.travelgo.controller;

import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.service.BookingService;
import com.travelgo.service.PaymentService;
import com.travelgo.service.ReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequestMapping("/staff/reports")
public class StaffReportController {

    private final BookingService bookingService;
    private final PaymentService paymentService;
    private final ReportService reportService;

    public StaffReportController(BookingService bookingService, PaymentService paymentService, ReportService reportService) {
        this.bookingService = bookingService;
        this.paymentService = paymentService;
        this.reportService = reportService;
    }

    @GetMapping("/bookings")
    public ResponseEntity<byte[]> exportBookings(@RequestParam(defaultValue = "excel") String format) {
        List<Booking> bookings = bookingService.findAll();
        byte[] data;
        String filename;
        MediaType mediaType;

        if ("pdf".equalsIgnoreCase(format)) {
            data = reportService.generateBookingsPdf(bookings);
            filename = "bookings_report.pdf";
            mediaType = MediaType.APPLICATION_PDF;
        } else {
            data = reportService.generateBookingsExcel(bookings);
            filename = "bookings_report.xlsx";
            mediaType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(data);
    }

    @GetMapping("/payments")
    public ResponseEntity<byte[]> exportPayments(@RequestParam(defaultValue = "excel") String format) {
        List<Payment> payments = paymentService.findAll();
        byte[] data;
        String filename;
        MediaType mediaType;

        if ("pdf".equalsIgnoreCase(format)) {
            data = reportService.generatePaymentsPdf(payments);
            filename = "payments_report.pdf";
            mediaType = MediaType.APPLICATION_PDF;
        } else {
            data = reportService.generatePaymentsExcel(payments);
            filename = "payments_report.xlsx";
            mediaType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(data);
    }

    @GetMapping("/kpis")
    public ResponseEntity<byte[]> exportKpis(@RequestParam(defaultValue = "excel") String format) {
        List<Booking> bookings = bookingService.findAll();
        byte[] data;
        String filename;
        MediaType mediaType;

        if ("pdf".equalsIgnoreCase(format)) {
            data = reportService.generateKpiPdf(bookings);
            filename = "kpi_report.pdf";
            mediaType = MediaType.APPLICATION_PDF;
        } else {
            data = reportService.generateKpiExcel(bookings);
            filename = "kpi_report.xlsx";
            mediaType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(mediaType)
                .body(data);
    }
}
