package com.travelgo;

import com.travelgo.config.AccountEmailDispatcher;
import com.travelgo.entity.OutboundEmail;
import com.travelgo.entity.User;
import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.service.AccountEmailDispatchService;
import com.travelgo.service.AccountEmailService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic transaction callbacks and mocked persistence only; no application, database or email provider. */
class AccountEmailSchedulingTests {
    final Clock clock = Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"), ZoneOffset.UTC);
    final OutboundEmailRepository emails = mock(OutboundEmailRepository.class);
    final AccountEmailDispatchService dispatch = mock(AccountEmailDispatchService.class);
    final TemplateEngine templates = mock(TemplateEngine.class);

    @AfterEach void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test void verificationSchedulesOnlyAfterCommitAndPreservesTheTokenAndRenderedMessage() {
        AccountEmailService service = service();
        User user = new User(); user.setName("Synthetic traveler"); user.setEmail("traveler@example.invalid");
        TransactionSynchronizationManager.initSynchronization();
        service.verification(user, "synthetic-verification-token", "synthetic-verification-hash");
        verifyNoInteractions(dispatch);
        ArgumentCaptor<OutboundEmail> row = ArgumentCaptor.forClass(OutboundEmail.class);
        verify(emails).saveAndFlush(row.capture());
        assertEquals("account:verification:synthetic-verification-hash", row.getValue().getEventKey());
        assertEquals("QUEUED", row.getValue().getStatus());
        assertEquals(clock.instant(), row.getValue().getNextAttemptAt());
        assertEquals("<p>Synthetic account email</p>", row.getValue().getHtmlBody());
        ArgumentCaptor<IContext> variables = ArgumentCaptor.forClass(IContext.class);
        verify(templates).process(eq("email/account"), variables.capture());
        assertEquals("https://travelgo.example.invalid/auth/verify-email?token=synthetic-verification-token", variables.getValue().getVariable("link"));
        assertTrue(variables.getValue().getVariable("guidance").toString().contains("24 hours"));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(dispatch).submit(91L);
    }

    @Test void rollbackDoesNotScheduleRecoveryOrChangeItsThirtyMinuteGuidance() {
        AccountEmailService service = service();
        User user = new User(); user.setName("Synthetic traveler"); user.setEmail("traveler@example.invalid");
        TransactionSynchronizationManager.initSynchronization();
        service.recovery(user, "synthetic-reset-token", "synthetic-reset-hash");
        ArgumentCaptor<IContext> variables = ArgumentCaptor.forClass(IContext.class);
        verify(templates).process(eq("email/account"), variables.capture());
        assertEquals("https://travelgo.example.invalid/auth/reset-password?token=synthetic-reset-token", variables.getValue().getVariable("link"));
        assertTrue(variables.getValue().getVariable("guidance").toString().contains("30 minutes"));
        TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(dispatch);
    }

    @Test void pollSubmitsOnlyDueAccountMessagesAndKeepsBookingDispatchPaused() throws Exception {
        when(dispatch.isConfigured()).thenReturn(true);
        OutboundEmail row = new OutboundEmail(); ReflectionTestUtils.setField(row, "id", 92L);
        when(emails.findTop20ByStatusAndEventKeyStartingWithAndNextAttemptAtLessThanEqualOrderById("QUEUED", "account:", clock.instant()))
                .thenReturn(List.of(row));
        new AccountEmailDispatcher(emails, dispatch, clock).dispatch();
        verify(dispatch).submit(92L);
        verify(emails, never()).findTop20ByStatusAndNextAttemptAtLessThanEqualOrderById(anyString(), any());
        Scheduled schedule = AccountEmailDispatcher.class.getMethod("dispatch").getAnnotation(Scheduled.class);
        assertEquals("${travelgo.mail.account-poll-delay-ms:1000}", schedule.fixedDelayString());
        assertEquals("${travelgo.mail.account-poll-delay-ms:1000}", schedule.initialDelayString());
    }

    @Test void unconfiguredTransportDoesNotReadTheDurableQueue() {
        when(dispatch.isConfigured()).thenReturn(false);
        new AccountEmailDispatcher(emails, dispatch, clock).dispatch();
        verifyNoInteractions(emails);
        verify(dispatch, never()).submit(anyLong());
    }

    private AccountEmailService service() {
        when(templates.process(eq("email/account"), any(IContext.class))).thenReturn("<p>Synthetic account email</p>");
        when(emails.saveAndFlush(any(OutboundEmail.class))).thenAnswer(call -> {
            OutboundEmail row = call.getArgument(0); ReflectionTestUtils.setField(row, "id", 91L); return row;
        });
        return new AccountEmailService(emails, templates, dispatch, clock, "https://travelgo.example.invalid/");
    }
}
