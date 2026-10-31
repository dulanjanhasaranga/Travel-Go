package com.travelgo.controller;

import com.travelgo.config.ContentSecurityPolicyFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class ContentSecurityPolicyAdvice {
    @ModelAttribute("cspNonce") public String nonce(HttpServletRequest request) {
        Object value = request.getAttribute(ContentSecurityPolicyFilter.NONCE_ATTRIBUTE);
        return value instanceof String nonce ? nonce : "";
    }
}
