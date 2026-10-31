package com.travelgo;

import com.travelgo.entity.OutboundEmail;
import com.travelgo.repository.OutboundEmailRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Selected-feature browser fixture; never packaged into the application JAR. */
public class SelectedScopeBrowserPreview {
    public static void main(String[] args) {
        System.setProperty("spring.devtools.restart.enabled", "false");
        new SpringApplication(TravelGoApplication.class, PlatformBrowserPreview.Fixtures.class,
                MailExamples.class).run(
                "--spring.profiles.active=test", "--spring.config.import=",
                "--server.address=127.0.0.1", "--server.port=8082",
                "--server.servlet.session.cookie.name=TRAVELGO_CLIENT_PREVIEW",
                "--spring.datasource.url=jdbc:h2:mem:travelgo-client-preview;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=create-drop", "--spring.jpa.open-in-view=true",
                "--travelgo.mail.enabled=false", "--travelgo.mail.dispatch-enabled=false",
                "--travelgo.payhere.enabled=false", "--travelgo.stripe.enabled=false",
                "--travelgo.payments.simulated=true",
                "--travelgo.upload-directory=target/client-selected-preview-uploads",
                "--spring.thymeleaf.cache=false");
    }

    @TestConfiguration
    @Import(TestDatabaseSafetyConfiguration.class)
    static class MailExamples {
        @Bean
        ApplicationListener<ApplicationReadyEvent> syntheticMailExamples(
                OutboundEmailRepository messages, PlatformTransactionManager manager, Clock clock) {
            return event -> {
                if (!event.getApplicationContext().getEnvironment().getProperty("spring.datasource.url", "")
                        .startsWith("jdbc:h2:mem:travelgo-client-preview;")) {
                    throw new IllegalStateException("Browser examples require the dedicated in-memory preview.");
                }
                new TransactionTemplate(manager).executeWithoutResult(transaction -> {
                    for (String status : List.of("QUEUED", "REVIEW_REQUIRED")) {
                        OutboundEmail message = new OutboundEmail();
                        message.setEventKey("browser-example-" + status);
                        message.setRecipient("preview@example.invalid");
                        message.setSubject("Synthetic preview: " + status.toLowerCase().replace('_', ' '));
                        message.setHtmlBody("Synthetic private message body. No real email or account link.");
                        message.setStatus(status);
                        message.setCreatedAt(clock.instant());
                        message.setNextAttemptAt(clock.instant());
                        message.setAttempts("REVIEW_REQUIRED".equals(status) ? 1 : 0);
                        messages.save(message);
                    }
                });
            };
        }
    }
}
