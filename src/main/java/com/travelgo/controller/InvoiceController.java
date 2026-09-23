package com.travelgo.controller;

import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.entity.User;
import com.travelgo.service.BookingService;
import com.travelgo.service.PaymentService;
import com.travelgo.service.UserService;
import com.travelgo.service.InvoicePdfGenerator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Optional;

@Controller
@RequestMapping("/customer/bookings/{bookingId}/invoice")
public class InvoiceController {

    private final BookingService bookingService;
    private final PaymentService paymentService;
    private final UserService userService;
    private final InvoicePdfGenerator pdfGenerator;

    public InvoiceController(BookingService bookingService, PaymentService paymentService, UserService userService, InvoicePdfGenerator pdfGenerator) {
        this.bookingService = bookingService;
        this.paymentService = paymentService;
        this.userService = userService;
        this.pdfGenerator = pdfGenerator;
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<byte[]> downloadInvoice(@PathVariable Long bookingId, @PathVariable Long paymentId, @AuthenticationPrincipal UserDetails userDetails) {
        User user = userService.findByEmail(userDetails.getUsername());
        Booking booking = bookingService.getBookingById(bookingId);
        
        if (booking == null || !booking.getUser().getId().equals(user.getId())) {
            return ResponseEntity.notFound().build();
        }

        Optional<Payment> paymentOpt = paymentService.findById(paymentId);
        if (paymentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        Payment payment = paymentOpt.get();
        if (!payment.getBooking().getId().equals(bookingId)) {
            return ResponseEntity.notFound().build();
        }

        byte[] pdfBytes = pdfGenerator.generateInvoice(booking, payment);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "invoice-INV-" + paymentId + ".pdf");

        return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);
    }
}
