package com.travelgo.controller;

import com.travelgo.dto.RegisterRequest;
import com.travelgo.config.DemoRequestAccess;
import com.travelgo.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for authentication pages (login, register).
 * Login POST is handled by Spring Security - only the page rendering and
 * registration logic are handled here.
 */
@Controller
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final com.travelgo.service.DemoAccountService demoAccounts;
    private final com.travelgo.service.AccountRecoveryService accountRecoveryService;
    private final boolean googleLoginEnabled;

    public AuthController(UserService userService, com.travelgo.service.DemoAccountService demoAccounts,
                          com.travelgo.service.AccountRecoveryService accountRecoveryService,
                          @org.springframework.beans.factory.annotation.Value("${travelgo.auth.google.enabled:false}") boolean googleLoginEnabled) {
        this.userService = userService;
        this.demoAccounts = demoAccounts;
        this.accountRecoveryService = accountRecoveryService;
        this.googleLoginEnabled = googleLoginEnabled;
    }

    @GetMapping("/login")
    public String loginPage(Model model, HttpServletRequest request) {
        model.addAttribute("demoChoices", DemoRequestAccess.isLocal(request) ? demoAccounts.choices() : java.util.List.of());
        model.addAttribute("googleLoginEnabled", googleLoginEnabled);
        addRegistrationAvailability(model);
        return "auth/login";
    }

    @GetMapping("/register")
    public String registerPage(Model model) {
        model.addAttribute("registerRequest", new RegisterRequest());
        addRegistrationAvailability(model);
        return "auth/register";
    }

    // An access-denied forward retains the original request method.
    @RequestMapping("/access-denied")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.FORBIDDEN)
    public String accessDeniedPage() {
        return "auth/access-denied";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("registerRequest") RegisterRequest request,
                           BindingResult result,
                           Model model,
                           RedirectAttributes redirectAttributes) {

        addRegistrationAvailability(model);
        if (!userService.isEmailVerificationConfigured()) {
            model.addAttribute("errorMessage",
                    "Registration is temporarily unavailable. Contact the agency for help.");
            return "auth/register";
        }

        // Check password confirmation match
        if (!java.util.Objects.equals(request.getPassword(), request.getConfirmPassword())) {
            result.rejectValue("confirmPassword", "error.confirmPassword", "Enter the same password in both fields.");
        }

        if (result.hasErrors()) {
            return "auth/register";
        }

        try {
            userService.registerCustomer(
                    request.getName(),
                    request.getEmail(),
                    request.getPassword(),
                    request.getPhone(),
                    request.getAddress()
            );
            redirectAttributes.addFlashAttribute("successMessage",
                    "Account created. Open the verification link in your email before signing in.");
            return "redirect:/auth/login";
        } catch (RuntimeException e) {
            model.addAttribute("errorMessage", e instanceof IllegalArgumentException ? e.getMessage() : "We could not create your account. If you already have an account, sign in or reset your password.");
            return "auth/register";
        }
    }

    @GetMapping("/verify-email")
    public String verifyEmailPage(@org.springframework.web.bind.annotation.RequestParam(value = "token", required = false) String token, Model model) {
        // Mail scanners follow GET links. Only a deliberate, CSRF-protected POST may activate the account.
        model.addAttribute("token", token != null && !token.isBlank() && token.length() <= 256 ? token : null);
        return "auth/verify-email";
    }

    @PostMapping("/verify-email")
    public String verifyEmail(@org.springframework.web.bind.annotation.RequestParam("token") String token, RedirectAttributes redirectAttributes) {
        try {
            userService.verifyEmail(token);
            redirectAttributes.addFlashAttribute("successMessage", "Email verified. You can now sign in.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", com.travelgo.service.CustomerErrorMessage.from(e));
        }
        return "redirect:/auth/login";
    }

    @GetMapping("/resend-verification")
    public String resendVerificationPage(Model model) {
        addRegistrationAvailability(model);
        return "auth/resend-verification";
    }

    @PostMapping("/resend-verification")
    public String resendVerification(@org.springframework.web.bind.annotation.RequestParam("email") String email,
                                     RedirectAttributes redirectAttributes) {
        try {
            userService.resendVerification(email);
            redirectAttributes.addFlashAttribute("successMessage",
                    "If this address has an account awaiting verification, a verification email will be sent. Check your inbox and spam folder. Wait one minute before requesting another email.");
        } catch (RuntimeException unavailable) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Email verification is temporarily unavailable. Contact the agency for help.");
        }
        return "redirect:/auth/resend-verification";
    }

    private void addRegistrationAvailability(Model model) {
        model.addAttribute("registrationAvailable", userService.isEmailVerificationConfigured());
    }
}


