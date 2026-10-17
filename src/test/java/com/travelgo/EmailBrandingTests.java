package com.travelgo;

import com.travelgo.entity.OutboundEmail;
import com.travelgo.repository.OutboundEmailRepository;
import com.travelgo.service.EmailDeliveryService;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real template/MIME composition with synthetic values and a mocked mail transport; no Spring or DB. */
class EmailBrandingTests {
    @Test void composedEmailEmbedsTheSuppliedTransparentLogoWithoutRemoteImageRequests() throws Exception {
        var emails = mock(OutboundEmailRepository.class);
        var sender = mock(JavaMailSender.class);
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<JavaMailSender>) mock(ObjectProvider.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(provider.getIfAvailable()).thenReturn(sender);
        when(provider.getObject()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var email = new OutboundEmail();
        email.setEventKey("booking:synthetic-brand-check");
        email.setRecipient("preview@example.invalid");
        email.setSubject("Synthetic booking update");
        email.setHtmlBody(render("transaction", transactionValues()));
        email.setNextAttemptAt(Instant.EPOCH);
        when(emails.lock(1L)).thenReturn(Optional.of(email));
        new EmailDeliveryService(emails, provider, Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC),
                manager, "preview@travelgo.test", true).deliver(1L);
        var captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        var logo = findLogo(captured.getValue());
        assertNotNull(logo, "Email clients need the inline CID resource, not an external image URL.");
        assertTrue(logo.isMimeType("image/png"));
        try (var expected = new ClassPathResource("static/images/brand/travelgo-airborne.png").getInputStream();
             var actual = logo.getInputStream()) {
            assertArrayEquals(expected.readAllBytes(), actual.readAllBytes());
        }
        assertEquals("SENT", email.getStatus());
        assertEquals(1, email.getAttempts());
    }

    @Test void accountEmailKeepsEscapedCustomerTextAndTheExactActionLink() throws Exception {
        var values = new LinkedHashMap<String, Object>();
        values.put("heading", "Verify your TravelGO account");
        values.put("customerName", "Preview <script>alert(1)</script>");
        values.put("intro", "Verify your email address to activate your account.");
        values.put("linkLabel", "Verify email address");
        values.put("link", "https://agency.example.invalid/auth/verify-email?token=synthetic-preview-only");
        values.put("guidance", "This link expires after 24 hours. Ignore it if you did not create an account.");
        String html = render("account", values);
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("href=\"" + values.get("link") + "\""));
        assertTrue(html.contains("Verify email address"));
        assertTrue(html.contains("cid:travelgo-logo"));
        values.put("customerName", "Alex Morgan");
        exportPreview("verification", render("account", values));
    }

    @Test void passwordResetEmailKeepsItsOwnActionAndExpiryGuidance() throws Exception {
        var values = new LinkedHashMap<String, Object>();
        values.put("heading", "Reset your TravelGO password");
        values.put("customerName", "Alex Morgan");
        values.put("intro", "We received a request to reset the password for your TravelGO account.");
        values.put("linkLabel", "Reset password");
        values.put("link", "https://agency.example.invalid/auth/reset-password?token=synthetic-preview-only");
        values.put("guidance", "This link expires after 30 minutes. If you did not request a password reset, you can ignore this email.");
        String html = render("account", values);
        assertTrue(html.contains("href=\"" + values.get("link") + "\""));
        assertTrue(html.contains("Reset password"));
        assertTrue(html.contains("30 minutes"));
        assertFalse(html.contains("Verify email address"));
        exportPreview("password-reset", html);
    }

    @Test void transactionEmailKeepsPaymentDetailsAndSimulationNotice() throws Exception {
        String html = render("transaction", transactionValues());
        assertTrue(html.contains("USD 120.00"));
        assertTrue(html.contains("TG-PREVIEW-10"));
        assertTrue(html.contains("Payments here are simulated; no money is charged."));
        assertTrue(html.contains("href=\"https://agency.example.invalid/customer/bookings/10\""));
        assertTrue(html.contains("Preview &amp; family"));
        exportPreview("transaction", html);
    }

    @Test void transactionWithoutBookingLinkDoesNotInventAnActionOrPaymentNotice() {
        var values = transactionValues();
        values.put("link", null);
        String html = render("transaction", values);
        assertTrue(html.contains("TG-PREVIEW-10"));
        assertTrue(html.contains("USD 120.00"));
        assertFalse(html.contains("View booking details"));
        assertFalse(html.contains("Payments here are simulated"));
        assertFalse(html.contains("href=\"null\""));
    }

    private Map<String, Object> transactionValues() {
        var values = new LinkedHashMap<String, Object>();
        values.put("heading", "Your booking update"); values.put("customerName", "Preview & family");
        values.put("intro", "Your payment record is ready to view.");
        values.put("details", Map.of("Booking reference", "TG-PREVIEW-10", "Payment", "USD 120.00"));
        values.put("nextStep", "Open your booking to review the current travel arrangements.");
        values.put("link", "https://agency.example.invalid/customer/bookings/10");
        values.put("simulatedPayments", true); values.put("contact", "Synthetic preview - no email sent");
        return values;
    }

    private String render(String template, Map<String, Object> values) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var context = new Context(); context.setVariables(values);
        return engine.process(template, context);
    }

    private Part findLogo(Part part) throws Exception {
        if ("<travelgo-logo>".equals(part.getHeader("Content-ID") == null ? null : part.getHeader("Content-ID")[0])) return part;
        if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                var found = findLogo(multipart.getBodyPart(index)); if (found != null) return found;
            }
        }
        return null;
    }

    private void exportPreview(String name, String html) throws Exception {
        if (!Boolean.getBoolean("travelgo.audit.export-brand")) return;
        Path root = Path.of("target", "email-branding-release-20261014", "previews").toAbsolutePath().normalize();
        Files.createDirectories(root);
        // Synthetic fixtures only; external action links are disabled for browser review.
        byte[] logo = new ClassPathResource("static/images/brand/travelgo-airborne.png").getInputStream().readAllBytes();
        String preview = html.replace("cid:travelgo-logo", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(logo))
                .replace("<a ", "<a onclick=\"return false\" ");
        Files.writeString(root.resolve(name + ".html"), preview);
    }
}
