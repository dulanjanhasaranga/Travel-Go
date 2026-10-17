package com.travelgo;

import com.travelgo.entity.Role;
import com.travelgo.entity.SystemSettings;
import com.travelgo.repository.RoleRepository;
import com.travelgo.repository.SystemSettingsRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Temporary account-page browser preview; test sources are never packaged in the application JAR. */
public final class RegistrationBrowserPreview {
    private RegistrationBrowserPreview() { }

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("The isolated preview does not accept configuration overrides.");
        System.setProperty("spring.devtools.restart.enabled", "false");
        String database = "travelgo-registration-preview-" + UUID.randomUUID();
        new SpringApplication(TravelGoApplication.class, Preview.class).run(
                "--spring.profiles.active=test", "--spring.config.import=", "--spring.config.additional-location=",
                "--spring.config.location=classpath:/application.properties,classpath:/application-test.properties",
                "--server.address=127.0.0.1", "--server.port=8082",
                "--server.servlet.session.cookie.name=TRAVELGO_REGISTRATION_PREVIEW",
                "--spring.datasource.url=jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "--spring.jpa.hibernate.ddl-auto=create-drop",
                "--spring.jpa.open-in-view=true", "--spring.jpa.show-sql=false", "--spring.sql.init.mode=never",
                "--travelgo.demo.enabled=false", "--travelgo.data-initializer.enabled=false",
                "--travelgo.catalogue.install=false", "--travelgo.catalogue.restore-original=false",
                "--travelgo.bootstrap.admin.email=", "--travelgo.bootstrap.admin.password=", "--travelgo.bootstrap.admin.name=",
                "--travelgo.mail.enabled=true", "--travelgo.mail.dispatch-enabled=false", "--travelgo.mail.account-retry-enabled=false",
                "--travelgo.mail.feedback.brevo-enabled=false", "--spring.mail.test-connection=false",
                "--spring.mail.host=127.0.0.1", "--spring.mail.port=25252", "--spring.mail.username=", "--spring.mail.password=",
                "--spring.mail.properties.mail.debug=false", "--spring.mail.properties.mail.debug.auth=false",
                "--travelgo.mail.from=preview@travelgo.invalid", "--travelgo.public-base-url=http://127.0.0.1:8082",
                "--travelgo.payhere.enabled=false", "--travelgo.stripe.enabled=false", "--travelgo.payments.simulated=true",
                "--travelgo.auth.google.enabled=false", "--travelgo.upload-directory=target/registration-browser-preview-uploads",
                "--spring.thymeleaf.cache=false");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(TestDatabaseSafetyConfiguration.class)
    static class Preview {
        @Bean Capture capture() { return new Capture(); }

        @Bean JavaMailSender previewMail(Capture capture) {
            // A test double cannot open sockets. Messages and bearer links remain only in this process's memory.
            JavaMailSender sender = mock(JavaMailSender.class);
            when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
            doAnswer(call -> {
                MimeMessage message = call.getArgument(0);
                capture.messages.add(message);
                return null;
            }).when(sender).send(any(MimeMessage.class));
            return sender;
        }

        @Bean ApplicationRunner references(RoleRepository roles, SystemSettingsRepository settings) {
            return args -> {
                // Reference rows only: no user accounts, catalogue entries, bookings or queued messages.
                roles.saveAndFlush(new Role("CUSTOMER", "Isolated account-page preview"));
                SystemSettings site = new SystemSettings();
                site.setCompanyName("Travel Go"); site.setWebsiteStatus("ACTIVE");
                site.setCompanyEmail("preview@travelgo.invalid"); site.setCompanyAddress("Isolated browser preview");
                settings.saveAndFlush(site);
            };
        }
    }

    static final class Capture {
        final List<MimeMessage> messages = new CopyOnWriteArrayList<>();
    }
}
