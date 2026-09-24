package com.travelgo.controller;

import com.travelgo.dto.RegisterRequest;
import com.travelgo.service.UserService;
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

    public AuthController(UserService userService, com.travelgo.service.DemoAccountService demoAccounts, com.travelgo.service.AccountRecoveryService accountRecoveryService) {
        this.userService = userService;
        this.demoAccounts = demoAccounts;
        this.accountRecoveryService = accountRecoveryService;
    }

    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("demoChoices", demoAccounts.choices());
        return "auth/login";
    }

    @GetMapping("/register")
    public String registerPage(Model model) {
        model.addAttribute("registerRequest", new RegisterRequest());
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("registerRequest") RegisterRequest request,
                           BindingResult result,
                           Model model,
                           RedirectAttributes redirectAttributes) {

        // Check password confirmation match
        if (!java.util.Objects.equals(request.getPassword(), request.getConfirmPassword())) {
            result.rejectValue("confirmPassword", "error.confirmPassword", "Passwords do not match");
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
                    "Registration successful! Please log in with your credentials.");
            return "redirect:/auth/login";
        } catch (RuntimeException e) {
            model.addAttribute("errorMessage", e instanceof IllegalArgumentException ? e.getMessage() : "Registration could not be completed. Check whether your email is already registered.");
            return "auth/register";
        }
    }

    @GetMapping("/verify-email")
    public String verifyEmail(@org.springframework.web.bind.annotation.RequestParam("token") String token, RedirectAttributes redirectAttributes) {
        try {
            userService.verifyEmail(token);
            redirectAttributes.addFlashAttribute("successMessage", "Your email has been successfully verified! You may now log in.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Email verification failed: " + e.getMessage());
        }
        return "redirect:/auth/login";
    }
}


