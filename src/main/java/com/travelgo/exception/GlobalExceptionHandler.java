package com.travelgo.exception;

import com.travelgo.service.CustomerErrorMessage;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
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
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Set<String> REPLACED_ENTITY_HEADERS = Set.of("content-type", "content-length",
            "content-disposition", "content-encoding", "content-range", "accept-ranges", "etag",
            "last-modified", "transfer-encoding", "location");

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public org.springframework.http.ResponseEntity<Void> handleMethodNotAllowed(org.springframework.web.HttpRequestMethodNotSupportedException ex,
                                                                              HttpServletResponse response) {
        if (!prepareResponse(ex, response)) return null;
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED).build();
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Void> handleStatus(org.springframework.web.server.ResponseStatusException ex,
                                                                     HttpServletResponse response) {
        if (!prepareResponse(ex, response)) return null;
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).build();
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public org.springframework.http.ResponseEntity<Void> handleDenied(org.springframework.security.access.AccessDeniedException ex,
                                                                     HttpServletResponse response) {
        if (!prepareResponse(ex, response)) return null;
        return org.springframework.http.ResponseEntity.status(403).build();
    }

    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public org.springframework.http.ResponseEntity<Void> handleNotFound(Exception ex, HttpServletResponse response) {
        if (!prepareResponse(ex, response)) return null;
        return org.springframework.http.ResponseEntity.notFound().build();
    }

    @ExceptionHandler({org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.beans.TypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MethodArgumentNotValidException.class})
    public org.springframework.http.ResponseEntity<Void> handleBadRequest(Exception ex, HttpServletResponse response) {
        if (!prepareResponse(ex, response)) return null;
        return org.springframework.http.ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(RuntimeException.class)
    public String handleRuntimeException(RuntimeException ex, RedirectAttributes redirectAttributes,
                                         Model model, HttpServletResponse response) {
        if (!prepareResponse(ex, response)) return null;
        if (ex.getClass() == IllegalArgumentException.class || ex.getClass() == IllegalStateException.class) {
            redirectAttributes.addFlashAttribute("errorMessage", CustomerErrorMessage.from(ex));
            return "redirect:/";
        }
        return unexpectedError(ex, model, response);
    }

    @ExceptionHandler(Exception.class)
    public String handleGenericException(Exception ex, Model model, HttpServletResponse response) {
        if (!prepareResponse(ex, response)) return null;
        return unexpectedError(ex, model, response);
    }

    private boolean prepareResponse(Exception ex, HttpServletResponse response) {
        if (response.isCommitted()) {
            // A download/disconnected client may already have received bytes. Returning no view
            // leaves that response handled; attempting a second body cannot repair the transfer.
            logger.warn("Cannot replace a committed failure response; category {}", ex.getClass().getSimpleName());
            return false;
        }
        var retainedHeaders = new LinkedHashMap<String, ArrayList<String>>();
        for (String name : response.getHeaderNames()) {
            if (!REPLACED_ENTITY_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
                retainedHeaders.put(name, new ArrayList<>(response.getHeaders(name)));
        }
        // resetBuffer does not release the servlet's output-stream/writer selection. reset does,
        // so an uncommitted partial download can safely become HTML (or an empty HTTP error).
        // Keep session cookies, CSP and other security/protocol headers from the original chain.
        response.reset();
        retainedHeaders.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setHeader("Cache-Control", "no-store");
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        return true;
    }

    private String unexpectedError(Exception ex, Model model, HttpServletResponse response) {
        // Retain framework HTTP semantics without putting its diagnostic reason in the page.
        response.setStatus(ex instanceof org.springframework.web.ErrorResponse httpError
                ? httpError.getStatusCode().value() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        model.addAttribute("errorMessage", CustomerErrorMessage.from(ex));
        return "error";
    }
}
