package com.travelgo.config;
import com.travelgo.service.EmailDeliveryService;
import com.travelgo.repository.OutboundEmailRepository;
import java.time.Clock;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
@Configuration @EnableScheduling
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="travelgo.mail.dispatch-enabled",havingValue="true",matchIfMissing=true)
public class EmailDispatcher {
 private final OutboundEmailRepository emails;private final EmailDeliveryService delivery;private final Clock clock;
 public EmailDispatcher(OutboundEmailRepository emails,EmailDeliveryService delivery,Clock clock){this.emails=emails;this.delivery=delivery;this.clock=clock;}
 @Scheduled(fixedDelayString="${travelgo.mail.poll-delay-ms:30000}")
 public void dispatch(){if(!delivery.isConfigured())return;for(var e:emails.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderById("QUEUED",clock.instant()))delivery.deliver(e.getId());}
}
