package com.travelgo.util;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.transaction.TransactionSystemException;

import java.util.stream.Collectors;

public class ValidationHelper {

    /**
     * Extracts human-readable validation messages from exceptions.
     * Useful for catching JPA ConstraintViolationExceptions that are wrapped
     * in TransactionSystemExceptions during entity save/update.
     */
    public static String extractMessage(Exception e, String defaultMessage) {
        if (e instanceof IllegalArgumentException) {
            return e.getMessage();
        }

        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof ConstraintViolationException) {
                ConstraintViolationException cve = (ConstraintViolationException) cause;
                return cve.getConstraintViolations().stream()
                        .map(ConstraintViolation::getMessage)
                        .collect(Collectors.joining(". "));
            }
            cause = cause.getCause();
        }

        return defaultMessage;
    }
}
