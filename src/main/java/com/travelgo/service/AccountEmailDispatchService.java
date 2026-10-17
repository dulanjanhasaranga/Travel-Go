package com.travelgo.service;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/** Runs account SMTP work after commit without retaining the browser request's transaction resources. */
@Service
public class AccountEmailDispatchService implements DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(AccountEmailDispatchService.class);
    private final EmailDeliveryService delivery;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor;

    public AccountEmailDispatchService(EmailDeliveryService delivery) {
        this.delivery = delivery;
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(100), task -> {
                    Thread thread = new Thread(task, "travelgo-account-mail-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean isConfigured() { return delivery.isConfigured(); }

    /** A rejected submission remains durably queued; the account-only poll can recover it. */
    public boolean submit(Long id) {
        if (id == null || executor.isShutdown() || !inFlight.add(id)) return false;
        try {
            executor.execute(() -> {
                try { delivery.deliver(id); }
                catch (RuntimeException unavailable) {
                    // Do not log SMTP exception text: providers may include addresses or other private details.
                    logger.warn("Account email event {} needs a delivery status check", id);
                } finally { inFlight.remove(id); }
            });
            return true;
        } catch (RejectedExecutionException saturatedOrStopped) {
            inFlight.remove(id);
            logger.warn("Account email event {} remains queued for the next delivery poll", id);
            return false;
        }
    }

    @Override public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Work not started never claimed its durable row. An interrupted SMTP claim stays reviewable.
        inFlight.clear();
    }
}
