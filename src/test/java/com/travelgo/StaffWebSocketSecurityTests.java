package com.travelgo;

import com.travelgo.config.StaffWebSocketAuthorization;
import com.travelgo.config.StaffWebSocketSessions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.messaging.web.csrf.XorCsrfChannelInterceptor;
import org.springframework.security.messaging.web.socket.server.CsrfTokenHandshakeInterceptor;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Protocol authorization and masked CONNECT CSRF checks require no network or database. */
class StaffWebSocketSecurityTests {
    private final StaffWebSocketSessions sessions = mock(StaffWebSocketSessions.class);
    private final StaffWebSocketAuthorization authorization = new StaffWebSocketAuthorization(sessions);
    private final XorCsrfChannelInterceptor csrf = new XorCsrfChannelInterceptor();
    private final CsrfToken token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "isolated-session-csrf-token");
    private Map<String, Object> sessionAttributes;
    private String maskedToken;

    @BeforeEach
    void setUp() {
        when(sessions.isCurrent("protocol-session")).thenReturn(true);
        var request = new MockHttpServletRequest("GET", "/ws-travelgo");
        var response = new MockHttpServletResponse();
        var tokens = new HttpSessionCsrfTokenRepository();
        tokens.saveToken(token, request, response);
        request.setAttribute(DeferredCsrfToken.class.getName(), tokens.loadDeferredToken(request, response));
        new XorCsrfTokenRequestAttributeHandler().handle(request, response, () -> token);
        maskedToken = ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
        sessionAttributes = new HashMap<>();
        assertTrue(new CsrfTokenHandshakeInterceptor().beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response), new TextWebSocketHandler(), sessionAttributes));
    }

    @Test
    void handshakeKeepsTheSessionTokenAndConnectAcceptsItsMaskedPageValue() {
        assertEquals(token.getToken(), ((CsrfToken) sessionAttributes.get(CsrfToken.class.getName())).getToken());
        assertNotEquals(token.getToken(), maskedToken);
        var connect = message(StompCommand.CONNECT, staff("TRAVEL_CONSULTANT"), null, maskedToken);
        assertSame(connect, csrf.preSend(connect, null));
        assertSame(connect, authorization.preSend(connect, null));
    }

    @Test
    void allExistingStaffRolesCanConnectAndSubscribeToTheStaffFeed() {
        for (String role : List.of("ADMIN", "TRAVEL_CONSULTANT", "VISA_OFFICER")) {
            var connect = message(StompCommand.CONNECT, staff(role), null, maskedToken);
            assertSame(connect, csrf.preSend(connect, null));
            assertSame(connect, authorization.preSend(connect, null));
            var subscribe = message(StompCommand.SUBSCRIBE, staff(role), "/topic/staff-updates", null);
            assertSame(subscribe, authorization.preSend(subscribe, null));
        }
    }

    @Test
    void customersAnonymousAndUnauthenticatedPrincipalsCannotUseTheStaffFeed() {
        assertThrows(AccessDeniedException.class, () -> authorization.preSend(message(StompCommand.CONNECT, null, null, maskedToken), null));
        assertThrows(AccessDeniedException.class, () -> authorization.preSend(message(StompCommand.CONNECT,
                new UsernamePasswordAuthenticationToken("unauthenticated", "unused"), null, maskedToken), null));
        var customer = staff("CUSTOMER");
        assertThrows(AccessDeniedException.class, () -> authorization.preSend(message(StompCommand.CONNECT, customer, null, maskedToken), null));
        assertThrows(AccessDeniedException.class, () -> authorization.preSend(message(StompCommand.SUBSCRIBE, customer, "/topic/staff-updates", null), null));
    }

    @Test
    void evenStaffCannotPublishSpoofedMessagesOrSubscribeToUnknownDestinations() {
        var user = staff("TRAVEL_CONSULTANT");
        var connect = message(StompCommand.CONNECT, user, null, maskedToken);
        csrf.preSend(connect, null); authorization.preSend(connect, null);
        for (String destination : List.of("/topic/staff-updates", "/topic/other", "/app/bookings")) {
            assertThrows(AccessDeniedException.class, () -> authorization.preSend(message(StompCommand.SEND, user, destination, null), null));
        }
        for (String destination : List.of("/topic/other", "/topic/**", "/app/bookings")) {
            assertThrows(AccessDeniedException.class, () -> authorization.preSend(message(StompCommand.SUBSCRIBE, user, destination, null), null));
        }
    }

    @Test
    void subscriptionsRequireACompletedCsrfValidatedConnectAndStopAfterDisconnect() {
        var user = staff("TRAVEL_CONSULTANT");
        var subscribe = message(StompCommand.SUBSCRIBE, user, "/topic/staff-updates", null);
        assertThrows(AccessDeniedException.class, () -> authorization.preSend(subscribe, null));
        var connect = message(StompCommand.CONNECT, user, null, maskedToken);
        csrf.preSend(connect, null); authorization.preSend(connect, null);
        assertSame(subscribe, authorization.preSend(subscribe, null));
        authorization.preSend(message(StompCommand.DISCONNECT, user, null, null), null);
        assertThrows(AccessDeniedException.class, () -> authorization.preSend(subscribe, null));
    }

    @Test
    void missingIncorrectAndUnmaskedConnectTokensAreRejected() {
        var user = staff("VISA_OFFICER");
        assertThrows(CsrfException.class, () -> csrf.preSend(message(StompCommand.CONNECT, user, null, null), null));
        assertThrows(CsrfException.class, () -> csrf.preSend(message(StompCommand.CONNECT, user, null, "incorrect-token"), null));
        assertThrows(CsrfException.class, () -> csrf.preSend(message(StompCommand.CONNECT, user, null, token.getToken()), null));
        sessionAttributes.clear();
        assertThrows(CsrfException.class, () -> csrf.preSend(message(StompCommand.CONNECT, user, null, maskedToken), null));
    }

    @Test
    void maskedTokenFromADifferentSessionCannotAuthorizeConnect() {
        var another = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "different-session-csrf-value");
        var request = new MockHttpServletRequest();
        new XorCsrfTokenRequestAttributeHandler().handle(request, new MockHttpServletResponse(), () -> another);
        var wrongSession = ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
        assertThrows(CsrfException.class, () -> csrf.preSend(message(StompCommand.CONNECT, staff("TRAVEL_CONSULTANT"), null, wrongSession), null));
    }

    @Test
    void staffCanDisconnectButCannotHideADestinationInAControlFrame() {
        var disconnect = message(StompCommand.DISCONNECT, staff("VISA_OFFICER"), null, null);
        assertSame(disconnect, authorization.preSend(disconnect, null));
        assertThrows(AccessDeniedException.class, () -> authorization.preSend(message(StompCommand.CONNECT,
                staff("VISA_OFFICER"), "/topic/staff-updates", maskedToken), null));
    }

    private Authentication staff(String role) {
        return new UsernamePasswordAuthenticationToken("staff@example.invalid", "unused", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private Message<byte[]> message(StompCommand command, Authentication user, String destination, String csrfToken) {
        var headers = StompHeaderAccessor.create(command);
        headers.setSessionId("protocol-session");
        headers.setUser(user);
        headers.setSessionAttributes(sessionAttributes);
        if (destination != null) headers.setDestination(destination);
        if (csrfToken != null) headers.setNativeHeader(token.getHeaderName(), csrfToken);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
