package com.travelgo;

import com.travelgo.entity.OutboundEmail;
import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.service.AccountEmailEligibility;
import com.travelgo.service.AccountMailRetryPolicy;
import com.travelgo.service.EmailDeliveryService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Properties;
import org.eclipse.angus.mail.util.MailConnectException;
import org.eclipse.angus.mail.util.SocketConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure claim/MIME/transport tests: the SMTP transport, database and manager are mocked. */
class AccountMailRetryTests {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test void definiteConnectionFailureRequeuesAndWaitsUntilDueBeforeAnotherSend() {
        var fixture = new Fixture();
        doThrow(connectionFailure()).when(fixture.sender).send(any(MimeMessage.class));
        fixture.delivery.deliver(1L);
        assertEquals("QUEUED", fixture.email.getStatus());
        assertEquals(1, fixture.email.getAttempts());
        assertEquals(NOW.plusSeconds(2), fixture.email.getNextAttemptAt());
        fixture.delivery.deliver(1L);
        verify(fixture.sender, times(1)).send(any(MimeMessage.class));
    }

    @Test void successfulRetryKeepsOneEventAndSentMessagesCannotBeReplayed() {
        var fixture = new Fixture();
        fixture.email.setAttempts(1);
        fixture.delivery.deliver(1L);
        assertEquals("SENT", fixture.email.getStatus());
        assertEquals(2, fixture.email.getAttempts());
        fixture.delivery.deliver(1L);
        verify(fixture.sender, times(1)).send(any(MimeMessage.class));
        assertEquals(NOW, fixture.email.getSentAt());
    }

    @Test void exhaustedConnectionRetriesRequireReviewWithoutUnboundedSending() {
        var fixture = new Fixture(); fixture.email.setAttempts(2);
        doThrow(connectionFailure()).when(fixture.sender).send(any(MimeMessage.class));
        fixture.delivery.deliver(1L);
        assertEquals("REVIEW_REQUIRED", fixture.email.getStatus());
        assertEquals(3, fixture.email.getAttempts());
        fixture.delivery.deliver(1L);
        verify(fixture.sender, times(1)).send(any(MimeMessage.class));
    }

    @Test void sendTimeoutHasUncertainAcceptanceAndIsNeverAutomaticallyResent() {
        var fixture = new Fixture();
        doThrow(new MailSendException("Uncertain SMTP result", new SocketTimeoutException())).when(fixture.sender).send(any(MimeMessage.class));
        fixture.delivery.deliver(1L); fixture.delivery.deliver(1L);
        assertEquals("REVIEW_REQUIRED", fixture.email.getStatus());
        verify(fixture.sender, times(1)).send(any(MimeMessage.class));
    }

    @Test void authenticationFailureDoesNotCreateARepeatedLoginOrEmailLoop() {
        var fixture = new Fixture();
        doThrow(new MailAuthenticationException("Synthetic bad authentication")).when(fixture.sender).send(any(MimeMessage.class));
        fixture.delivery.deliver(1L); fixture.delivery.deliver(1L);
        assertEquals("REVIEW_REQUIRED", fixture.email.getStatus());
        verify(fixture.sender, times(1)).send(any(MimeMessage.class));
    }

    @Test void bookingDispatchDoesNotBecomeEnabledByTheAccountRetryPolicy() {
        var fixture = new Fixture(); fixture.email.setEventKey("booking:synthetic");
        doThrow(connectionFailure()).when(fixture.sender).send(any(MimeMessage.class));
        fixture.delivery.deliver(1L);
        assertEquals("REVIEW_REQUIRED", fixture.email.getStatus());
    }

    @Test void secondConnectionFailureUsesBoundedBackoffAndNestedSendFailuresAreNotAssumedSafe() {
        var email = new OutboundEmail(); email.setEventKey("account:recovery:synthetic"); email.setAttempts(2);
        assertEquals(5, AccountMailRetryPolicy.retryDelaySeconds(email, connectionFailure()).orElseThrow());
        assertTrue(AccountMailRetryPolicy.retryDelaySeconds(email,
                new MailSendException("Uncertain wrapper", new IllegalStateException(connectionFailure()))).isEmpty());
    }

    private static MailSendException connectionFailure() {
        return new MailSendException("Mail server connection failed", new MailConnectException(
                new SocketConnectException("Synthetic refused connection", new ConnectException(), "smtp.example.invalid", 587, 10000)));
    }

    private static final class Fixture {
        final OutboundEmail email = new OutboundEmail();
        final JavaMailSender sender = mock(JavaMailSender.class);
        final EmailDeliveryService delivery;
        @SuppressWarnings("unchecked") Fixture() {
            var emails = mock(OutboundEmailRepository.class);
            var provider = (ObjectProvider<JavaMailSender>) mock(ObjectProvider.class);
            var manager = mock(PlatformTransactionManager.class);
            var eligibility = mock(AccountEmailEligibility.class);
            when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
            when(provider.getIfAvailable()).thenReturn(sender); when(provider.getObject()).thenReturn(sender);
            when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
            email.setEventKey("account:recovery:synthetic"); email.setRecipient("preview@example.invalid");
            email.setSubject("Synthetic recovery test"); email.setHtmlBody("<p>Safe test</p>");
            email.setNextAttemptAt(NOW);
            when(emails.lock(1L)).thenReturn(Optional.of(email));
            delivery = new EmailDeliveryService(emails, provider, Clock.fixed(NOW, ZoneOffset.UTC), manager, "preview@example.invalid", true, eligibility);
        }
    }
}
