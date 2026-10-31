package com.travelgo.service;

import com.travelgo.config.BrevoMailConfiguration;
import com.travelgo.util.MailConfigurationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import static org.junit.jupiter.api.Assertions.*;

/** Bean selection/readiness only. No application, database or provider is started. */
class BrevoMailConfigurationTests {
    final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(BrevoMailConfiguration.class);

    @Test void unsetOrSmtpTransportAllowsBootToCreateOnlyTheSmtpSender() {
        for (String transport : new String[] {"", "smtp"}) {
            var configured = context.withPropertyValues("spring.mail.host=smtp.example.test", "spring.mail.port=25252");
            if (!transport.isEmpty()) configured = configured.withPropertyValues("travelgo.mail.transport=" + transport);
            configured.run(result -> {
                assertNull(result.getStartupFailure());
                assertEquals(1, result.getBeansOfType(JavaMailSender.class).size());
                var smtp = assertInstanceOf(JavaMailSenderImpl.class, result.getBean(JavaMailSender.class));
                assertFalse(smtp instanceof TransactionalMailSender);
                assertEquals("smtp.example.test", smtp.getHost()); assertEquals(25252, smtp.getPort());
                assertEquals(0, result.getBeansOfType(BrevoTransactionalMailSender.class).size());
            });
        }
    }

    @Test void unsetOrSmtpTransportKeepsExistingSenderAndDoesNotCreateApiAdapter() {
        var smtp = new JavaMailSenderImpl(); smtp.setHost("127.0.0.1"); smtp.setPort(25252);
        for (String transport : new String[] {"", "smtp"}) {
            var configured = context.withBean(JavaMailSender.class, () -> smtp);
            if (!transport.isEmpty()) configured = configured.withPropertyValues("travelgo.mail.transport=" + transport);
            configured.run(result -> {
                assertNull(result.getStartupFailure()); assertSame(smtp, result.getBean(JavaMailSender.class));
                assertEquals(0, result.getBeansOfType(BrevoTransactionalMailSender.class).size());
            });
        }
    }

    @Test void explicitApiSelectionUsesSeparateKeyAndPublicHttpsOriginForReadiness() {
        context.withPropertyValues("travelgo.mail.transport=brevo-api",
                "travelgo.mail.brevo-api-key=" + BrevoTransactionalMailSenderTests.KEY,
                "travelgo.public-base-url=" + BrevoTransactionalMailSenderTests.ORIGIN).run(result -> {
            assertNull(result.getStartupFailure());
            JavaMailSender sender = result.getBean(JavaMailSender.class);
            assertInstanceOf(TransactionalMailSender.class, sender);
            assertTrue(MailConfigurationStatus.isConfigured(true, sender));
            assertFalse(MailConfigurationStatus.isConfigured(false, sender));
        });
    }

    @Test void explicitApiSelectionMakesSmtpAutoConfigurationBackOff() {
        context.withPropertyValues("travelgo.mail.transport=brevo-api",
                "travelgo.mail.brevo-api-key=" + BrevoTransactionalMailSenderTests.KEY,
                "travelgo.public-base-url=" + BrevoTransactionalMailSenderTests.ORIGIN,
                "spring.mail.host=smtp.example.test", "spring.mail.port=25252").run(result -> {
            assertNull(result.getStartupFailure());
            assertEquals(1, result.getBeansOfType(JavaMailSender.class).size());
            assertInstanceOf(BrevoTransactionalMailSender.class, result.getBean(JavaMailSender.class));
            assertTrue(MailConfigurationStatus.isConfigured(true, result.getBean(JavaMailSender.class)));
        });
    }

    @Test void selectingApiWithoutItsOwnKeyCannotEnableAccountReadinessThroughSmtpProperties() {
        context.withPropertyValues("travelgo.mail.transport=brevo-api", "spring.mail.host=smtp.example.test",
                "spring.mail.password=xsmtpsib-synthetic_smtp_key_123456789",
                "travelgo.public-base-url=" + BrevoTransactionalMailSenderTests.ORIGIN).run(result -> {
            assertNull(result.getStartupFailure());
            assertFalse(MailConfigurationStatus.isConfigured(true, result.getBean(JavaMailSender.class)));
        });
    }
}
