package com.travelgo;

import com.travelgo.config.ContentSecurityPolicyFilter;
import com.travelgo.controller.ContentSecurityPolicyAdvice;
import jakarta.servlet.DispatcherType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;

class ContentSecurityPolicyTests {
    @Test void nonceIsFreshForEachResponseIncludingTheSameSessionAndIgnoresClientHeaders() throws Exception {
        var filter = new ContentSecurityPolicyFilter(true, "http://localhost:8081");
        var session = new MockHttpSession();
        String previous = null;
        for (int i = 0; i < 12; i++) {
            var request = new MockHttpServletRequest();
            request.setSession(session);
            request.addHeader("X-CSP-Nonce", "attacker-chosen");
            request.addHeader("Host", "attacker.example.test");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                assertNotNull(req.getAttribute(ContentSecurityPolicyFilter.NONCE_ATTRIBUTE));
            });
            String nonce = (String)request.getAttribute(ContentSecurityPolicyFilter.NONCE_ATTRIBUTE);
            assertEquals(32, Base64.getDecoder().decode(nonce).length);
            assertNotEquals(previous, nonce);
            assertNotEquals("attacker-chosen", nonce);
            String policy = response.getHeader("Content-Security-Policy");
            assertNotNull(policy);
            assertTrue(policy.contains("script-src 'nonce-" + nonce + "'; script-src-attr 'none'"));
            assertFalse(policy.contains("unsafe-eval"));
            assertFalse(policy.contains("strict-dynamic"));
            assertFalse(policy.contains("attacker.example"));
            assertTrue(policy.contains("object-src 'none'; base-uri 'none'; frame-ancestors 'none'"));
            assertNull(response.getHeader("Content-Security-Policy-Report-Only"));
            previous = nonce;
        }
    }

    @Test void configuredHttpsWebSocketOriginAndOnlyRequiredCrossOriginFeaturesAreAllowed() throws Exception {
        var filter = new ContentSecurityPolicyFilter(true, "https://travel-go.example.test:8443");
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {});
        String policy = response.getHeader("Content-Security-Policy");
        assertTrue(policy.contains("connect-src 'self' wss://travel-go.example.test:8443;"));
        assertFalse(policy.contains("ws://localhost"));
        assertTrue(policy.contains("https://maps.google.com"));
        assertTrue(policy.contains("form-action 'self' https://sandbox.payhere.lk https://www.payhere.lk https://checkout.stripe.com;"));
        assertFalse(policy.contains("script-src 'self'"));
        assertFalse(policy.matches("(?s).*script-src[^;]*unsafe-inline.*"));
    }

    @Test void malformedOriginsCannotCreateAnInjectedOrBroadPolicy() {
        for (String origin : new String[]{"https://good.test/path", "https://user:pass@good.test", "javascript:alert(1)",
                "https://good.test/?x=1", "https://good.test/#hash", "https://bad.test;unsafe-inline", "https://good.test:0",
                "https://good.test:65536", "https://good.test\r\nscript-src *", "file:///tmp"})
            assertThrows(IllegalArgumentException.class, () -> new ContentSecurityPolicyFilter(true, origin), origin);
        assertDoesNotThrow(() -> new ContentSecurityPolicyFilter(true, "http://[::1]:8081"));
    }

    @Test void errorDispatchKeepsTheSameNonceForTheSameDocument() throws Exception {
        var filter = new ContentSecurityPolicyFilter(true, "http://localhost:8081");
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        String original = response.getHeader("Content-Security-Policy");
        request.setDispatcherType(DispatcherType.ERROR);
        response.reset();
        filter.doFilter(request, response, (req, res) -> {});
        assertEquals(original, response.getHeader("Content-Security-Policy"));
        assertEquals(request.getAttribute(ContentSecurityPolicyFilter.NONCE_ATTRIBUTE), new ContentSecurityPolicyAdvice().nonce(request));
    }

    @Test void localDisableIsExplicitAndAdviceHasNoRequestParameterFallback() throws Exception {
        var filter = new ContentSecurityPolicyFilter(false, "http://localhost:8081");
        var request = new MockHttpServletRequest();
        request.addParameter("cspNonce", "attacker-chosen");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        assertNull(response.getHeader("Content-Security-Policy"));
        assertNull(request.getAttribute(ContentSecurityPolicyFilter.NONCE_ATTRIBUTE));
        assertEquals("", new ContentSecurityPolicyAdvice().nonce(request));
        assertFalse(filter.enabled());
    }

    @Test void everyAuthoredBrowserScriptHasExplicitNonceAndNoHandlerCodeRemains() throws Exception {
        var scripts = Pattern.compile("<script\\b([^>]*?)>", Pattern.CASE_INSENSITIVE);
        var handlers = Pattern.compile("(?i)(?:\\s|:)on[a-z]+\\s*=");
        int count = 0;
        try (var paths = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".html")).toList()) {
                if (path.toString().contains("email" + java.io.File.separator)) continue;
                String template = Files.readString(path);
                assertFalse(handlers.matcher(template).find(), "Inline event code: " + path);
                assertFalse(template.contains("javascript:"), "Executable URL: " + path);
                var matcher = scripts.matcher(template);
                while (matcher.find()) {
                    assertTrue(matcher.group(1).contains("th:attr=\"nonce=${cspNonce}\""), "Missing explicit nonce: " + path);
                    count++;
                }
            }
        }
        assertTrue(count > 100, "Audit the full browser template tree");
        for (String name : new String[]{"main.js", "navigation.js", "ui-actions.js"}) {
            String script = Files.readString(Path.of("src/main/resources/static/js", name));
            assertFalse(script.contains("new Function("));
            assertFalse(script.contains("eval("));
            assertFalse(script.contains("createElement('script')"), "No response script recreation: " + name);
        }
    }
}
