package com.travelgo;

import com.travelgo.entity.OutboundEmail;
import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.service.EmailDeliveryService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A real SMTP wire exchange to an ephemeral loopback capture; no external recipients/provider. */
class SmtpTransportVerificationTests {
    final Clock clock = Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"), ZoneOffset.UTC);
    final OutboundEmailRepository emails = mock(OutboundEmailRepository.class);
    final ObjectProvider<JavaMailSender> sender = mock(ObjectProvider.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    EmailDeliveryService service(JavaMailSender mail, boolean enabled) {
        when(sender.getIfAvailable()).thenReturn(mail); when(sender.getObject()).thenReturn(mail);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new EmailDeliveryService(emails, sender, clock, manager, "tests@travelgo.test", enabled);
    }
    OutboundEmail email() {
        OutboundEmail email = new OutboundEmail(); org.springframework.test.util.ReflectionTestUtils.setField(email, "id", 1L);
        email.setEventKey("smtp-wire-test"); email.setRecipient("capture@travelgo.test"); email.setSubject("TravelGO transport verification");
        email.setHtmlBody("<p>Local transport test only</p>"); email.setNextAttemptAt(clock.instant());
        email.setAttachmentName("test-receipt.pdf"); email.setAttachmentData("%PDF-1.4 test artifact".getBytes(StandardCharsets.UTF_8));
        when(emails.lock(1L)).thenReturn(Optional.of(email)); return email;
    }
    @Test void durableMessageLogoAndReceiptReachOnlyTheLoopbackCaptureOnce() throws Exception {
        try (LocalSmtp capture = new LocalSmtp()) {
            JavaMailSenderImpl mail = new JavaMailSenderImpl(); mail.setHost("127.0.0.1"); mail.setPort(capture.port());
            mail.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "3000"); mail.getJavaMailProperties().setProperty("mail.smtp.timeout", "3000");
            OutboundEmail email = email(); EmailDeliveryService service = service(mail, true); service.deliver(1L); service.deliver(1L);
            MimeMessage received = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(capture.message().getBytes(StandardCharsets.UTF_8)));
            assertEquals("TravelGO transport verification", received.getSubject()); assertEquals("capture@travelgo.test", received.getAllRecipients()[0].toString());
            assertEquals("<travelgo-1@notifications.travelgo>", received.getMessageID());
            assertNotNull(email.getFeedbackReference());
            assertEquals("travelgo:" + email.getFeedbackReference(), received.getHeader("X-Mailin-custom", null));
            assertTrue(email.getFeedbackReference().matches("[A-Za-z0-9_-]{43}"));
            assertTrue(capture.message().contains("travelgo-logo")); assertTrue(capture.message().contains("test-receipt.pdf"));
            assertEquals("SENT", email.getStatus()); assertEquals(clock.instant(), email.getSentAt()); assertEquals(1, email.getAttempts());
        }
    }
    @Test void disabledOrHostlessTransportNeverClaimsAnEmail() {
        JavaMailSenderImpl hostless = new JavaMailSenderImpl(); assertFalse(service(hostless, true).isConfigured());
        assertFalse(service(mock(JavaMailSender.class), false).isConfigured()); service(hostless, true).deliver(1L); verifyNoInteractions(emails);
    }
    @Test void smtpFailureRequiresReviewAndDoesNotAutomaticallyResend() {
        JavaMailSender failing = mock(JavaMailSender.class); when(failing.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new org.springframework.mail.MailSendException("ambiguous accepted-or-failed delivery")).when(failing).send(any(MimeMessage.class));
        OutboundEmail email = email(); EmailDeliveryService service = service(failing, true); service.deliver(1L); service.deliver(1L);
        assertEquals("REVIEW_REQUIRED", email.getStatus()); assertEquals(1, email.getAttempts()); assertNull(email.getSentAt()); verify(failing, times(1)).send(any(MimeMessage.class));
    }
    @Test void authenticatedFeedbackDuringSmtpFailurePreventsAnUncertainResend() {
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        OutboundEmail email = email();
        doAnswer(invocation -> {
            // Simulates a row-locked callback recording acceptance before SMTP disconnects.
            email.setStatus("SENT"); email.setRecipientDeliveryStatus("DELIVERED"); email.setSentAt(clock.instant());
            throw new org.springframework.mail.MailSendException("acknowledgement was lost");
        }).when(mail).send(any(MimeMessage.class));
        EmailDeliveryService service = service(mail, true); service.deliver(1L); service.deliver(1L);
        assertEquals("SENT", email.getStatus()); assertEquals("DELIVERED", email.getRecipientDeliveryStatus());
        assertEquals(1, email.getAttempts()); verify(mail, times(1)).send(any(MimeMessage.class));
    }
    static class LocalSmtp implements AutoCloseable {
        final ServerSocket server; final ExecutorService worker = Executors.newSingleThreadExecutor(); final Future<String> message;
        LocalSmtp() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")); server.setSoTimeout(5000);
            message = worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000); var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
                    send(writer, "220 loopback-capture"); StringBuilder body = new StringBuilder(); boolean data = false;
                    for (String line; (line = reader.readLine()) != null;) {
                        if (data) { if (".".equals(line)) { data = false; send(writer, "250 queued locally"); } else body.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n"); }
                        else if (line.startsWith("EHLO") || line.startsWith("HELO")) send(writer, "250 loopback-capture");
                        else if (line.equals("DATA")) { send(writer, "354 message follows"); data = true; }
                        else if (line.equals("QUIT")) { send(writer, "221 bye"); return body.toString(); }
                        else if (line.startsWith("MAIL FROM:") || line.startsWith("RCPT TO:") || line.equals("RSET")) send(writer, "250 ok");
                        else send(writer, "500 unsupported in test capture");
                    }
                    return body.toString();
                }
            });
        }
        int port() { return server.getLocalPort(); }
        String message() throws Exception { return message.get(10, TimeUnit.SECONDS); }
        static void send(BufferedWriter writer, String line) throws IOException { writer.write(line + "\r\n"); writer.flush(); }
        public void close() throws IOException { server.close(); worker.shutdownNow(); }
    }
}
