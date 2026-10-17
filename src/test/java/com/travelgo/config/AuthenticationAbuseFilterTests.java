package com.travelgo.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class AuthenticationAbuseFilterTests {
    private final AtomicLong time = new AtomicLong();
    private final AuthenticationRequestLimiter limiter = new AuthenticationRequestLimiter(
            AuthenticationAbuseProperties.defaults(), time::get);
    private final AuthenticationAbuseFilter filter = new AuthenticationAbuseFilter(limiter);
    private final AtomicInteger downstream = new AtomicInteger();

    @Test void newBrowserSessionsDoNotBypassTheSourceAndAccountBudgets() throws Exception {
        for (int i = 0; i < 10; i++) assertEquals(200, submit("/auth/login", "192.0.2.20", "person@example.test").getStatus());
        var response = submit("/auth/login", "192.0.2.20", "person@example.test");
        assertEquals(429, response.getStatus());
        assertEquals(10, downstream.get());
        assertEquals("900", response.getHeader("Retry-After"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("text/html;charset=UTF-8", response.getContentType());
        assertFalse(response.getContentAsString().contains("person@example.test"));
        assertFalse(response.getContentAsString().contains("192.0.2.20"));
        assertFalse(response.getContentAsString().contains("never-reflect-password"));
    }

    @Test void clientSuppliedForwardingHeadersCannotChangeTheBudget() throws Exception {
        for (int i = 0; i < 11; i++) {
            var request = request("POST", "/auth/login", "192.0.2.21", "person@example.test");
            request.addHeader("X-Forwarded-For", "198.51.100." + i);
            request.addHeader("Forwarded", "for=198.51.100." + i);
            request.addHeader("X-Real-IP", "198.51.100." + i);
            assertEquals(i < 10 ? 200 : 429, execute(request).getStatus());
        }
    }

    @Test void forgotAndResendShareTheirEmailBudget() throws Exception {
        assertEquals(200, submit("/auth/forgot-password", "192.0.2.22", "person@example.test").getStatus());
        assertEquals(200, submit("/auth/resend-verification", "192.0.2.22", "person@example.test").getStatus());
        assertEquals(200, submit("/auth/forgot-password", "192.0.2.22", "person@example.test").getStatus());
        assertEquals(429, submit("/auth/resend-verification", "192.0.2.22", "person@example.test").getStatus());
    }

    @Test void resetAndVerificationShareTheirSourceBudgetAndNeverReflectTokens() throws Exception {
        for (int i = 0; i < 30; i++) assertEquals(200, submit(i % 2 == 0 ? "/auth/reset-password" : "/auth/verify-email",
                "192.0.2.23", null).getStatus());
        var response = submit("/auth/verify-email", "192.0.2.23", null);
        assertEquals(429, response.getStatus());
        assertFalse(response.getContentAsString().contains("never-reflect-token"));
    }

    @Test void unrelatedPostsGetRequestsAndLogoutRemainUnaffected() throws Exception {
        for (String method : new String[] {"GET", "HEAD", "POST"}) {
            for (String route : new String[] {"/", "/auth/logout", "/contact", "/api/webhooks/payhere"}) {
                assertEquals(200, execute(request(method, route, "192.0.2.24", "person@example.test")).getStatus());
            }
        }
        assertEquals(200, execute(request("GET", "/auth/login", "192.0.2.24", "person@example.test")).getStatus());
        assertEquals(0, limiter.trackedBuckets());
    }

    @Test void budgetsWorkWhenApplicationIsMountedUnderAContextPath() throws Exception {
        for (int i = 0; i < 11; i++) {
            var request = request("POST", "/travelgo/auth/login", "192.0.2.25", "person@example.test");
            request.setContextPath("/travelgo");
            assertEquals(i < 10 ? 200 : 429, execute(request).getStatus());
        }
    }

    @Test void blockedAttemptsDoNotExtendTheExpiry() throws Exception {
        for (int i = 0; i < 10; i++) submit("/auth/login", "192.0.2.26", "person@example.test");
        time.set(899_999);
        assertEquals("1", submit("/auth/login", "192.0.2.26", "person@example.test").getHeader("Retry-After"));
        time.set(900_000);
        assertEquals(200, submit("/auth/login", "192.0.2.26", "person@example.test").getStatus());
    }

    private MockHttpServletResponse submit(String route, String source, String email) throws Exception {
        return execute(request("POST", route, source, email));
    }

    private static MockHttpServletRequest request(String method, String route, String source, String email) {
        var request = new MockHttpServletRequest(method, route);
        request.setRemoteAddr(source);
        request.setSession(new MockHttpSession());
        if (email != null) request.addParameter("email", email);
        request.addParameter("password", "never-reflect-password");
        request.addParameter("token", "never-reflect-token");
        return request;
    }

    private MockHttpServletResponse execute(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> downstream.incrementAndGet());
        return response;
    }
}
