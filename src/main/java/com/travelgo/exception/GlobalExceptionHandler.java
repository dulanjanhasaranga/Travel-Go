package com.travelgo.exception;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Global exception handler for the application.
 * Catches common exceptions and provides user-friendly error messages.
 */
@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public org.springframework.http.ResponseEntity<Void> handleMethodNotAllowed(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED).build();
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Void> handleStatus(org.springframework.web.server.ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).build();
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public org.springframework.http.ResponseEntity<Void> handleDenied(org.springframework.security.access.AccessDeniedException ex) {
        return org.springframework.http.ResponseEntity.status(403).build();
    }


    @ExceptionHandler(RuntimeException.class)
    public String handleRuntimeException(RuntimeException ex, RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        return "redirect:/";
    }

    @ExceptionHandler(Exception.class)
    public String handleGenericException(Exception ex, Model model) {
        model.addAttribute("errorMessage", "An unexpected error occurred. Please try again.");
        return "error";
    }
}
