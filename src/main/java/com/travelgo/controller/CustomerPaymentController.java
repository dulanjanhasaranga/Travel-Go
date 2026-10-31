package com.travelgo.controller;

import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import com.travelgo.entity.User;
import com.travelgo.entity.VisaApplication;
import com.travelgo.enums.PaymentType;
import com.travelgo.service.BookingService;
import com.travelgo.service.PaymentService;
import com.travelgo.service.StripeService;
import com.travelgo.service.UserService;
import com.travelgo.service.VisaApplicationService;
import com.travelgo.service.PayHereService;
import com.travelgo.dto.PayHereBillingRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/customer/payments")
public class CustomerPaymentController {

    private final PaymentService paymentService;
    private final BookingService bookingService;
    private final UserService userService;
    private final VisaApplicationService visaApplicationService;
    private final StripeService stripeService;
    private final PayHereService payHereService;
    
    @Value("${travelgo.public-base-url}")
    private String publicBaseUrl;

    @Value("${travelgo.payments.simulated:true}")
    private boolean simulatedPayments;

    @Value("${travelgo.stripe.enabled:false}")
    private boolean stripeEnabled;

    public CustomerPaymentController(PaymentService paymentService,
                                    BookingService bookingService,
                                    UserService userService,
                                    VisaApplicationService visaApplicationService,
                                    StripeService stripeService,PayHereService payHereService) {
        this.paymentService = paymentService;
        this.bookingService = bookingService;
        this.userService = userService;
        this.visaApplicationService = visaApplicationService;
        this.stripeService = stripeService;
        this.payHereService = payHereService;
    }

    private User resolveUser(UserDetails userDetails) {
        if (userDetails == null) return null;
        return userService.getUserByEmail(userDetails.getUsername()).orElse(null);
    }

    @GetMapping("/checkout/{bookingId}")
    public String checkoutPage(@PathVariable("bookingId") Long bookingId,
                              @RequestParam(value = "success", required = false) String success,
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

            // Browser callbacks are read-only; only a protected POST or verified webhook records payment.
            if ("true".equals(success)) {
                model.addAttribute("infoMessage", "Checkout returned. Your payment records below show the verified payment status.");
            } else if ("true".equals(canceled)) {
                model.addAttribute("infoMessage", "Checkout was canceled. No payment has been recorded from this return page.");
            }

            model.addAttribute("booking", booking);
            model.addAttribute("payments", paymentService.findByBookingId(bookingId));
            model.addAttribute("currentUser", user);
            
            VisaApplication visa = visaApplicationService.findByUserId(user.getId()).stream()
                    .filter(v -> v.getBooking().getId().equals(bookingId))
                    .findFirst().orElse(null);
            model.addAttribute("visaApplication", visa);
            model.addAttribute("totalVisaCharge", visa != null ? visa.getTotalCharge() : BigDecimal.ZERO);
            model.addAttribute("totalFinalCharge", paymentService.expectedAmount(booking, PaymentType.FULL_PACKAGE));
            model.addAttribute("canPayVisa", paymentService.canPay(booking, PaymentType.VISA_DOCUMENTATION));
            model.addAttribute("canPayFinal", paymentService.canPay(booking, PaymentType.FULL_PACKAGE));
            model.addAttribute("simulatedPayments", simulatedPayments);
            model.addAttribute("stripeAvailable", stripeEnabled && stripeService.isConfigured());
            model.addAttribute("stripeTestMode", !stripeService.isLiveMode());
            model.addAttribute("payHereAvailable",payHereService.isConfigured());
            model.addAttribute("payHereTestMode",payHereService.isTestMode());

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
                                @RequestParam(value = "demoCardResult", required = false) String demoCardResult,
                                @AuthenticationPrincipal UserDetails userDetails,
                                jakarta.servlet.http.HttpServletRequest request,
                                Model model,
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

            // Submitted amounts are quote checks; persistence always recalculates under the booking lock.
            BigDecimal expected = paymentService.expectedAmount(booking, type);
            if (expected.compareTo(amount) != 0) {
                 throw new IllegalArgumentException("The amount does not match the current charge. Refresh the payment page before continuing.");
            }

            if (simulatedPayments) {
                // Only an opaque demo result crosses this boundary. Raw card
                // fields are never part of the local demonstration contract.
                java.util.Set<String> demoFields = java.util.Set.of("bookingId", "paymentType", "amount", "demoCardResult", "_csrf");
                if (!demoFields.containsAll(request.getParameterMap().keySet()))
                    throw new IllegalArgumentException("Card details are not accepted by TravelGO. Use the local demo form without sending card fields.");
                if (!"APPROVED".equals(demoCardResult) && !"DECLINED".equals(demoCardResult))
                    throw new IllegalArgumentException("Complete the demo card form before recording a test payment.");
                if ("DECLINED".equals(demoCardResult)) {
                    paymentService.recordPaymentFailure(booking, type, "Simulated payment", null);
                    redirectAttributes.addFlashAttribute("errorMessage", "Demo card declined. No money was charged. Choose Approved to demonstrate a successful retry.");
                    return "redirect:/customer/payments/checkout/" + bookingId;
                }
                Payment payment = new Payment();
                payment.setBooking(booking);
                payment.setPaymentType(type);
                payment.setAmount(expected);
                payment.setPaymentMethod("Simulated payment");
                paymentService.processPayment(payment);
                redirectAttributes.addFlashAttribute("successMessage", "Simulated payment recorded. No money was charged.");
                return "redirect:/customer/payments/checkout/" + bookingId;
            }
            if (!paymentService.canPay(booking, type)) throw new IllegalStateException("Payment is unavailable at this stage. Refresh the page and check your visa status and payment deadline.");
            if(payHereService.isConfigured()){
                var billing=new PayHereBillingRequest();billing.setBookingId(bookingId);billing.setPaymentType(type.name());billing.setAmount(expected);
                String[] names=user.getName().trim().split("\\s+",2);billing.setFirstName(names[0]);if(names.length>1)billing.setLastName(names[1]);
                billing.setEmail(user.getEmail());billing.setPhone(user.getPhone());billing.setAddress(user.getAddress());
                model.addAttribute("billing",billing);model.addAttribute("booking",booking);model.addAttribute("payHereTestMode",payHereService.isTestMode());
                return "customer/payhere-billing";
            }
            if (!stripeEnabled) throw new IllegalStateException("Online payment is unavailable. Contact the agency for payment assistance.");
            String successUrl = publicBaseUrl + "/customer/payments/checkout/" + bookingId + "?success=true";
            String cancelUrl = publicBaseUrl + "/customer/payments/checkout/" + bookingId + "?canceled=true";
            
            java.time.LocalDateTime deadline = type == PaymentType.FULL_PACKAGE ? booking.getPackagePaymentDeadline()
                    : visaApplicationService.findByUserId(user.getId()).stream()
                    .filter(v -> v.getBooking().getId().equals(bookingId)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Visa charges are unavailable.")).getPaymentExpiresAt();
            String checkoutUrl = stripeService.createCheckoutSession(booking, type, expected, deadline, successUrl, cancelUrl);
            
            return "redirect:" + checkoutUrl;
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
            return "redirect:/customer/payments/checkout/" + bookingId;
        }
    }
    @PostMapping("/payhere")
    public String payHereCheckout(@jakarta.validation.Valid @ModelAttribute("billing") PayHereBillingRequest billing,
            org.springframework.validation.BindingResult errors,@AuthenticationPrincipal UserDetails details,Model model,
            jakarta.servlet.http.HttpServletResponse response,RedirectAttributes flash){
        User user=resolveUser(details);if(user==null)return "redirect:/auth/login";
        try{
            Booking booking=bookingService.findById(billing.getBookingId()).orElseThrow(()->new IllegalArgumentException("Booking not found."));
            if(!booking.getUser().getId().equals(user.getId()))throw new org.springframework.security.access.AccessDeniedException("Access denied.");
            model.addAttribute("booking",booking);model.addAttribute("payHereTestMode",payHereService.isTestMode());
            if(errors.hasErrors())return "customer/payhere-billing";
            PaymentType type=PaymentType.valueOf(billing.getPaymentType());BigDecimal expected=paymentService.expectedAmount(booking,type);
            java.time.LocalDateTime deadline=type==PaymentType.FULL_PACKAGE?booking.getPackagePaymentDeadline():visaApplicationService.findByUserId(user.getId()).stream()
                    .filter(v->v.getBooking().getId().equals(booking.getId())).findFirst().orElseThrow(()->new IllegalStateException("Visa charges are unavailable.")).getPaymentExpiresAt();
            var checkout=payHereService.checkout(booking,type,expected,deadline,billing);model.addAttribute("checkout",checkout);
            response.setHeader("Referrer-Policy","strict-origin-when-cross-origin");response.setHeader("Cache-Control","no-store");
            return "customer/payhere-forward";
        }catch(Exception error){flash.addFlashAttribute("errorMessage",com.travelgo.service.CustomerErrorMessage.from(error));return "redirect:/customer/payments/checkout/"+billing.getBookingId();}
    }
}
