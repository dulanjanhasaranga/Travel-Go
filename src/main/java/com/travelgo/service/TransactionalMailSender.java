package com.travelgo.service;

import com.travelgo.entity.OutboundEmail;
import org.springframework.mail.javamail.JavaMailSender;

/** Optional transport used only after the durable outbox claim has committed. */
public interface TransactionalMailSender extends JavaMailSender {
    boolean isConfigured();

    /** Returns the provider's message ID; it is acceptance evidence, not recipient delivery. */
    String sendTransactional(OutboundEmail email, String from);
}
