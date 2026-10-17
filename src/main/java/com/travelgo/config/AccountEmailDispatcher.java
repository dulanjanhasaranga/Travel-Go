package com.travelgo.config;

import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.service.AccountEmailDispatchService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Recovers committed account messages even while the general transactional queue is paused. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "travelgo.mail.enabled", havingValue = "true")
@ConditionalOnProperty(name = "travelgo.mail.account-retry-enabled", havingValue = "true", matchIfMissing = true)
public class AccountEmailDispatcher {
    private final OutboundEmailRepository emails;
    private final AccountEmailDispatchService delivery;
    private final Clock clock;
    public AccountEmailDispatcher(OutboundEmailRepository emails, AccountEmailDispatchService delivery, Clock clock) {
        this.emails = emails; this.delivery = delivery; this.clock = clock;
    }
    @Scheduled(fixedDelayString = "${travelgo.mail.account-poll-delay-ms:1000}",
            initialDelayString = "${travelgo.mail.account-poll-delay-ms:1000}")
    public void dispatch() {
        if (!delivery.isConfigured()) return;
        for (var email : emails.findTop20ByStatusAndEventKeyStartingWithAndNextAttemptAtLessThanEqualOrderById(
                "QUEUED", "account:", clock.instant())) delivery.submit(email.getId());
    }
}
