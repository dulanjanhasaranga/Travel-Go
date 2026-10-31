package com.travelgo.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Exercises only mocked delivery work. No application context, database or SMTP connection is opened. */
class AccountEmailDispatchServiceTests {
    @Test void requestReturnsWhileSmtpIsBlockedAndWorkUsesDedicatedThreads() throws Exception {
        EmailDeliveryService delivery = mock(EmailDeliveryService.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        doAnswer(call -> { worker.set(Thread.currentThread()); entered.countDown(); release.await(); return null; })
                .when(delivery).deliver(11L);
        AccountEmailDispatchService dispatch = new AccountEmailDispatchService(delivery);
        var request = Executors.newSingleThreadExecutor();
        try {
            assertTrue(request.submit(() -> dispatch.submit(11L)).get(1, TimeUnit.SECONDS));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(worker.get().getName().startsWith("travelgo-account-mail-"));
            assertTrue(worker.get().isDaemon());
            assertFalse(dispatch.submit(11L), "Polling must not enqueue an ID already waiting on SMTP");
            verify(delivery, times(1)).deliver(11L);
        } finally { release.countDown(); request.shutdownNow(); dispatch.destroy(); }
    }

    @Test void simultaneousSubmissionsForOneEventCreateOneWorkerTask() throws Exception {
        EmailDeliveryService delivery = mock(EmailDeliveryService.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), start = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(); return null; }).when(delivery).deliver(12L);
        AccountEmailDispatchService dispatch = new AccountEmailDispatchService(delivery);
        var requests = Executors.newFixedThreadPool(8);
        try {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 16; i++) calls.add(requests.submit(() -> { start.await(); return dispatch.submit(12L); }));
            start.countDown();
            int accepted = 0;
            for (var call : calls) if (call.get(2, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            verify(delivery, times(1)).deliver(12L);
        } finally { start.countDown(); release.countDown(); requests.shutdownNow(); dispatch.destroy(); }
    }

    @Test void saturatedQueueRejectsPromptlyWithoutUsingTheRequestThread() throws Exception {
        EmailDeliveryService delivery = mock(EmailDeliveryService.class);
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(); return null; }).when(delivery).deliver(anyLong());
        AccountEmailDispatchService dispatch = new AccountEmailDispatchService(delivery);
        var request = Executors.newSingleThreadExecutor();
        try {
            assertTrue(dispatch.submit(1L)); assertTrue(dispatch.submit(2L));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            for (long id = 3; id <= 102; id++) assertTrue(dispatch.submit(id));
            assertFalse(request.submit(() -> dispatch.submit(103L)).get(1, TimeUnit.SECONDS));
            verify(delivery, times(2)).deliver(anyLong());
            verify(delivery, never()).deliver(103L);
            release.countDown();
            assertTrue(eventuallySubmit(dispatch, 103L), "Rejected IDs must be recoverable by the durable queue poll");
        } finally { release.countDown(); request.shutdownNow(); dispatch.destroy(); }
        verify(delivery, times(1)).deliver(103L);
    }

    @Test void aPreclaimFailureReleasesTheIdForDurableQueueRecovery() throws Exception {
        EmailDeliveryService delivery = mock(EmailDeliveryService.class);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("Synthetic preclaim failure");
            finished.countDown(); return null;
        }).when(delivery).deliver(15L);
        AccountEmailDispatchService dispatch = new AccountEmailDispatchService(delivery);
        try {
            assertTrue(dispatch.submit(15L));
            assertTrue(eventuallySubmit(dispatch, 15L));
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        } finally { dispatch.destroy(); }
    }

    @Test void closedExecutorRejectsAllWorkWithoutCallingSmtp() {
        EmailDeliveryService delivery = mock(EmailDeliveryService.class);
        AccountEmailDispatchService dispatch = new AccountEmailDispatchService(delivery);
        dispatch.destroy();
        assertFalse(dispatch.submit(17L)); assertFalse(dispatch.submit(null));
        verifyNoInteractions(delivery);
    }

    private static boolean eventuallySubmit(AccountEmailDispatchService dispatch, Long id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (dispatch.submit(id)) return true;
            Thread.sleep(5);
        }
        return false;
    }
}
