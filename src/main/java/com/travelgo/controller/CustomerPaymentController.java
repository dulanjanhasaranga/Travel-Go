package com.travelgo.controller;

import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.entity.User;
import com.travelgo.entity.VisaApplication;
import com.travelgo.enums.PaymentStatus;
import com.travelgo.enums.PaymentType;
import com.travelgo.service.BookingService;
import com.travelgo.service.PaymentService;
import com.travelgo.service.StripeService;
import com.travelgo.service.UserService;
import com.travelgo.service.VisaApplicationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.UUID;

@Controller
@RequestMapping("/customer/payments")
public class CustomerPaymentController {

    private final PaymentService paymentService;
    private final BookingService bookingService;
    private final UserService userService;
    private final VisaApplicationService visaApplicationService;
    private final StripeService stripeService;
    
    @Value("${travelgo.public-base-url}")
    private String publicBaseUrl;

    public CustomerPaymentController(PaymentService paymentService,
                                    BookingService bookingService,
                                    UserService userService,
                                    VisaApplicationService visaApplicationService,
                                    StripeService stripeService) {
        this.paymentService = paymentService;
        this.bookingService = bookingService;
        this.userService = userService;
        this.visaApplicationService = visaApplicationService;
        this.stripeService = stripeService;
    }

    private User resolveUser(UserDetails userDetails) {
        if (userDetails == null) return null;
        return userService.getUserByEmail(userDetails.getUsername()).orElse(null);
    }

    @GetMapping("/checkout/{bookingId}")
    public String checkoutPage(@PathVariable("bookingId") Long bookingId,
                              @RequestParam(value = "success", required = false) String success,
                              @RequestParam(value = "simulated", required = false) String simulated,
                              @RequestParam(value = "paymentType", required = false) String paramPaymentType,
                              @RequestParam(value = "amount", required = false) BigDecimal paramAmount,
                              @RequestParam(value = "session_id", required = false) String sessionId,
                              @RequestParam(value = "canceled", required = false) String canceled,
                              @AuthenticationPrincipal UserDetails userDetails,
                              Model model,
                              RedirectAttributes redirectAttributes) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        return bookingService.findById(bookingId).map(booking -> {
            if (!booking.getUser().getId().equals(user.getId())) {
                redirectAttributes.addFlashAttribute("errorMessage", "Access denied.");
                return "redirect:/customer/bookings";
            }

            // Handle simulated fallback return from StripeService
            if ("true".equals(success) && "true".equals(simulated) && paramPaymentType != null && paramAmount != null) {
                try {
                    Payment payment = new Payment();
                    payment.setBooking(booking);
                    payment.setPaymentType(PaymentType.valueOf(paramPaymentType));
                    payment.setAmount(paramAmount);
                    payment.setPaymentMethod("Simulated Stripe Checkout");
                    payment.setTransactionReference("SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
                    paymentService.processPayment(payment);
                    model.addAttribute("successMessage", "Simulated payment processed successfully! (Add real Stripe keys to test real payments)");
                } catch (Exception e) {
                    model.addAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
                }
            } else if ("true".equals(success)) {
                if (sessionId != null && !sessionId.isEmpty()) {
                    try {
                        com.stripe.model.checkout.Session session = stripeService.verifySession(sessionId);
                        if (session != null && "paid".equals(session.getPaymentStatus())) {
                            PaymentType type = PaymentType.valueOf(session.getMetadata().get("paymentType"));
                            BigDecimal amount = new BigDecimal(session.getAmountTotal()).divide(new BigDecimal("100"));
                            String reference = session.getPaymentIntent();
                            if (reference == null || reference.isEmpty()) {
                                reference = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                            }
                            Payment payment = new Payment();
                            payment.setBooking(booking);
                            payment.setPaymentType(type);
                            payment.setAmount(amount);
                            payment.setPaymentMethod("Stripe Checkout");
                            payment.setTransactionReference(reference);
                            paymentService.processPayment(payment);
                            model.addAttribute("successMessage", "Payment processed successfully via Stripe!");
                        } else {
                            model.addAttribute("errorMessage", "Payment session verification failed or payment not completed.");
                        }
                    } catch (Exception e) {
                        model.addAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
                    }
                } else {
                    model.addAttribute("successMessage", "Payment processed successfully via Stripe!");
                }
            } else if ("true".equals(canceled)) {
                try {
                    PaymentType type = PaymentType.FULL_PACKAGE;
                    if (paramPaymentType != null) {
                        try { type = PaymentType.valueOf(paramPaymentType); } catch (Exception ignored) {}
                    }
                    paymentService.recordPaymentFailure(booking, type, "Stripe Checkout", "FAIL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
                } catch (Exception e) {
                    // Ignore exception if it fails to log
                }
                model.addAttribute("errorMessage", "Payment was canceled or declined. Please try again.");
            }

            model.addAttribute("booking", booking);
            model.addAttribute("payments", paymentService.findByBookingId(bookingId));
            model.addAttribute("currentUser", user);
            
            VisaApplication visa = visaApplicationService.findByUserId(user.getId()).stream()
                    .filter(v -> v.getBooking().getId().equals(bookingId))
                    .findFirst().orElse(null);
            model.addAttribute("visaApplication", visa);
            model.addAttribute("totalVisaCharge", visa != null ? visa.getTotalCharge() : BigDecimal.ZERO);
            model.addAttribute("totalDepositCharge", paymentService.expectedAmount(booking, PaymentType.PACKAGE_DEPOSIT));
            model.addAttribute("totalBalanceCharge", paymentService.expectedAmount(booking, PaymentType.PACKAGE_BALANCE));
            model.addAttribute("canPayVisa", paymentService.canPay(booking, PaymentType.VISA_DOCUMENTATION));
            model.addAttribute("canPayDeposit", paymentService.canPay(booking, PaymentType.PACKAGE_DEPOSIT));
            model.addAttribute("canPayBalance", paymentService.canPay(booking, PaymentType.PACKAGE_BALANCE));

            return "customer/payment-checkout";
        }).orElseGet(() -> {
            redirectAttributes.addFlashAttribute("errorMessage", "Booking not found.");
            return "redirect:/customer/bookings";
        });
    }

    @PostMapping("/process")
    public String processPayment(@RequestParam("bookingId") Long bookingId,
                                @RequestParam("paymentType") String paymentType,
                                @RequestParam("amount") BigDecimal amount,
                                @AuthenticationPrincipal UserDetails userDetails,
                                RedirectAttributes redirectAttributes) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        try {
            Booking booking = bookingService.findById(bookingId)
                    .orElseThrow(() -> new IllegalArgumentException("Booking not found"));

            if (!booking.getUser().getId().equals(user.getId())) {
                redirectAttributes.addFlashAttribute("errorMessage", "Access denied.");
                return "redirect:/customer/bookings";
            }

            PaymentType type = PaymentType.valueOf(paymentType);

            // Double check quoting to prevent users manipulating form values before sending to Stripe
            BigDecimal expected = paymentService.expectedAmount(booking, type);
            if (expected.compareTo(amount) != 0) {
                 throw new IllegalArgumentException("The quoted amount has changed or is incorrect. Refresh checkout.");
            }

            String successUrl = publicBaseUrl + "/customer/payments/checkout/" + bookingId + "?success=true&paymentType=" + type.name() + "&amount=" + amount;
            String cancelUrl = publicBaseUrl + "/customer/payments/checkout/" + bookingId + "?canceled=true&paymentType=" + type.name();
            
            String checkoutUrl = stripeService.createCheckoutSession(booking, type, amount, successUrl, cancelUrl);
            
            return "redirect:" + checkoutUrl;
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
            return "redirect:/customer/payments/checkout/" + bookingId;
        }
    }
}
