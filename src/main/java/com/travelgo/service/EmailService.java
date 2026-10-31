package com.travelgo.service;

import com.travelgo.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Async
    public void sendEmail(User recipient, String subject, String message) {
        log.debug("A legacy simulated email event was suppressed; transactional delivery uses the outbox service.");
    }

    @Async
    public void sendSms(User recipient, String message) {
        log.debug("A legacy simulated SMS event was suppressed.");
    }
}
