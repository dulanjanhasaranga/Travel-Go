package com.travelgo;

import com.travelgo.controller.CustomerBookingController;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.entity.User;
import com.travelgo.service.DepartureAvailability;
import com.travelgo.repository.PaymentRepository;
import com.travelgo.repository.SupplierHoldRepository;
import com.travelgo.service.BookingHotelService;
import com.travelgo.service.BookingService;
import com.travelgo.service.HotelService;
import com.travelgo.service.PdfReceiptService;
import com.travelgo.service.ReviewService;
import com.travelgo.service.TourPackageService;
import com.travelgo.service.TravelerService;
import com.travelgo.service.UserService;
import com.travelgo.service.VisaApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolated MVC coverage: the customer receipt never reads the application database. */
class CustomerReceiptControllerTests {
    private final BookingService bookings = mock(BookingService.class);
    private final UserService users = mock(UserService.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PdfReceiptService receipts = mock(PdfReceiptService.class);
    private MockMvc mvc;
    private Booking booking;
    private User owner;

    @BeforeEach
    void setUp() {
        CustomerBookingController controller = new CustomerBookingController(bookings, mock(TourPackageService.class), users,
                mock(HotelService.class), mock(BookingHotelService.class), mock(VisaApplicationService.class),
                mock(TravelerService.class), mock(ReviewService.class), mock(DepartureAvailability.class),
                mock(SupplierHoldRepository.class), receipts, payments, mock(com.travelgo.service.TravelPolicyService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        owner = new User();
        owner.setId(1L);
        owner.setEmail("receipt@example.invalid");
        booking = new Booking();
        booking.setId(10L);
        booking.setUser(owner);
        when(users.getUserByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(bookings.findById(booking.getId())).thenReturn(Optional.of(booking));
        var principal = org.springframework.security.core.userdetails.User.withUsername(owner.getEmail())
                .password("unused").roles("CUSTOMER").build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "unused", principal.getAuthorities()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingAndAnotherCustomersBookingReturnTheSameNotFoundResponse() throws Exception {
        mvc.perform(get("/customer/bookings/99/receipt")).andExpect(status().isNotFound());
        User anotherCustomer = new User();
        anotherCustomer.setId(2L);
        booking.setUser(anotherCustomer);
        mvc.perform(get("/customer/bookings/10/receipt")).andExpect(status().isNotFound());

        verifyNoInteractions(payments, receipts);
    }

    @Test
    void unpaidBookingReturnsAnExplanationWithoutGeneratingAPdf() throws Exception {
        when(payments.findByBooking_Id(booking.getId())).thenReturn(List.of());
        when(receipts.hasReceiptablePayment(List.of())).thenReturn(false);

        mvc.perform(get("/customer/bookings/10/receipt"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("A payment receipt is available after a successful payment."))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        verify(receipts, never()).generateReceipt(any(), anyList());
    }

    @Test
    void successfulReceiptDownloadsAsAPrivatePdfAttachment() throws Exception {
        List<Payment> successfulPayments = List.of(new Payment());
        byte[] pdf = "%PDF-test".getBytes(StandardCharsets.US_ASCII);
        when(payments.findByBooking_Id(booking.getId())).thenReturn(successfulPayments);
        when(receipts.hasReceiptablePayment(successfulPayments)).thenReturn(true);
        when(receipts.generateReceipt(booking, successfulPayments)).thenReturn(pdf);

        mvc.perform(get("/customer/bookings/10/receipt"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(pdf))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"receipt-10.pdf\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andReturn();
        verify(receipts).generateReceipt(booking, successfulPayments);
    }

    @Test
    void receiptGenerationFailureIsControlledAndDoesNotExposeInternalDetails() throws Exception {
        List<Payment> successfulPayments = List.of(new Payment());
        when(payments.findByBooking_Id(booking.getId())).thenReturn(successfulPayments);
        when(receipts.hasReceiptablePayment(successfulPayments)).thenReturn(true);
        when(receipts.generateReceipt(booking, successfulPayments)).thenThrow(new IllegalStateException("internal database diagnostic"));

        mvc.perform(get("/customer/bookings/10/receipt"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("Your receipt is currently unavailable. Contact the travel team to review your payment records."))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    }

    @Test
    void unauthenticatedReceiptRequestCannotReadBookingOrPaymentRecords() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/customer/bookings/10/receipt")).andExpect(status().isUnauthorized());
        verifyNoInteractions(bookings, payments, receipts);
    }
}
