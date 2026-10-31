package com.travelgo.controller;

import com.travelgo.config.EmailFeedbackConfiguration;
import com.travelgo.service.EmailFeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class EmailFeedbackController {
    private final EmailFeedbackConfiguration configuration;
    private final EmailFeedbackService service;
    public EmailFeedbackController(EmailFeedbackConfiguration configuration, EmailFeedbackService service) {
        this.configuration = configuration; this.service = service;
    }
    @PostMapping(value="/webhooks/email/brevo", consumes="application/json")
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        String authorization = request.getHeader("Authorization");
        configuration.authenticate(authorization);
        if (java.util.Collections.list(request.getHeaders("Authorization")).size() != 1)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook authentication required.");
        if (request.getContentLengthLong() > EmailFeedbackService.MAX_PAYLOAD_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        byte[] payload = request.getInputStream().readNBytes(EmailFeedbackService.MAX_PAYLOAD_BYTES + 1);
        if (payload.length > EmailFeedbackService.MAX_PAYLOAD_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        service.receive(authorization, payload);
        return ResponseEntity.noContent().build();
    }
}
