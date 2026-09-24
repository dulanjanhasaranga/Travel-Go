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
        log.info("==========================================");
        log.info("MOCK EMAIL DISPATCHED");
        log.info("To: {} ({})", recipient.getName(), recipient.getEmail());
        log.info("Subject: {}", subject);
        log.info("Body: {}", message);
        log.info("==========================================");
    }

    @Async
    public void sendSms(User recipient, String message) {
        log.info("==========================================");
        log.info("MOCK SMS DISPATCHED");
        log.info("To: {} ({})", recipient.getName(), recipient.getPhone());
        log.info("Message: {}", message);
        log.info("==========================================");
    }
}
