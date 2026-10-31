package com.travelgo;

import com.travelgo.config.DemoMode;
import com.travelgo.config.StaffWebSocketAccountAccess;
import com.travelgo.config.StaffWebSocketAuthorization;
import com.travelgo.config.StaffWebSocketSessions;
import com.travelgo.config.WebSocketConfig;
import com.travelgo.entity.Permission;
import com.travelgo.entity.Role;
import com.travelgo.entity.User;
import com.travelgo.repository.UserRepository;
import com.travelgo.service.CustomUserDetailsService;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Account, HTTP-session and transport revocation use only mocks; no Spring startup, DB or network. */
class StaffWebSocketRevocationTests {
    private final UserRepository users = mock(UserRepository.class);
    private final DemoMode demoMode = mock(DemoMode.class);
    private final StaffWebSocketAccountAccess accountAccess = new StaffWebSocketAccountAccess(users, demoMode);
    private final StaffWebSocketSessions sessions = new StaffWebSocketSessions(accountAccess);
    private Fixture staff;

    @BeforeEach
    void setUp() throws Exception {
        staff = fixture("staff@example.invalid", "staff-socket");
    }

    @Test
    void unchangedAccessReceivesFramesAndUsesAReadOnlyTransactionForPermissions() throws Exception {
        assertTrue(sessions.isCurrent(staff.socket().getId()));
        Message<?> event = event(staff.socket().getId());
        assertSame(event, sessions.outboundAuthorization().preSend(event, null));
        assertSame(event, ((ExecutorChannelInterceptor) sessions.outboundAuthorization()).beforeHandle(event, null, null));
        sessions.recheckSessions();
        verify(staff.socket(), never()).close(any());
        var transaction = StaffWebSocketAccountAccess.class.getMethod("isCurrent", Authentication.class,
                String.class, String.class, boolean.class).getAnnotation(Transactional.class);
        assertNotNull(transaction);
        assertTrue(transaction.readOnly());
    }

    @Test
    void enabledMfaWithoutAProofClosesAnAlreadyEstablishedSocket() throws Exception {
        var mfa = mock(com.travelgo.service.StaffMfaService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(sessions, "staffMfa", mfa);
        when(mfa.status(any(), any())).thenReturn(new com.travelgo.service.StaffMfaService.Status(true, true, false, false));
        assertFalse(sessions.isCurrent(staff.socket().getId()));
        verify(staff.socket(), times(1)).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void mfaExpiryOrRecoveryRotationSuppressesOutboundFramesAndClosesTheSocket() throws Exception {
        var mfa = mock(com.travelgo.service.StaffMfaService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(sessions, "staffMfa", mfa);
        when(mfa.status(any(), any())).thenReturn(new com.travelgo.service.StaffMfaService.Status(true, true, true, false));
        assertTrue(sessions.isCurrent(staff.socket().getId()));
        when(mfa.status(any(), any())).thenReturn(new com.travelgo.service.StaffMfaService.Status(true, true, false, false));
        assertNull(sessions.outboundAuthorization().preSend(event(staff.socket().getId()), null));
        verify(staff.socket(), times(1)).close(CloseStatus.POLICY_VIOLATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"suspended", "deleted", "role", "password", "permission-removed", "permission-added", "permission-renamed", "permission-id"})
    void staleAccountsLoseOutboundDeliveryAndTheirEstablishedSocketCloses(String change) throws Exception {
        switch (change) {
            case "suspended" -> staff.user().setActive(false);
            case "deleted" -> when(users.findByEmailIgnoreCase(staff.user().getEmail())).thenReturn(Optional.empty());
            case "role" -> staff.user().getRole().setRoleName("CUSTOMER");
            case "password" -> staff.user().setPassword("new-synthetic-password-hash");
            case "permission-removed" -> staff.user().getRole().getPermissions().clear();
            case "permission-added" -> staff.user().getRole().getPermissions().add(permission(12L, "PAYMENT_MANAGE"));
            case "permission-renamed" -> staff.user().getRole().getPermissions().iterator().next().setPermissionName("PAYMENT_MANAGE");
            case "permission-id" -> staff.user().getRole().getPermissions().iterator().next().setId(99L);
            default -> fail("Unknown fixture mutation");
        }
        assertNull(sessions.outboundAuthorization().preSend(event(staff.socket().getId()), null));
        assertFalse(sessions.isCurrent(staff.socket().getId()));
        verify(staff.socket(), times(1)).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void revocationAfterQueuingSuppressesInboundAndOutboundHandlers() throws Exception {
        Message<?> event = event(staff.socket().getId());
        var outbound = (ExecutorChannelInterceptor) sessions.outboundAuthorization();
        assertSame(event, outbound.preSend(event, null));
        staff.user().setActive(false);
        assertNull(outbound.beforeHandle(event, null, null));
        assertNull(new StaffWebSocketAuthorization(sessions).beforeHandle(event, null, null));
        assertThrows(AccessDeniedException.class, () -> new StaffWebSocketAuthorization(sessions).preSend(event, null));
        verify(staff.socket()).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void idleRevokedSocketClosesWithoutAnyClientOrServerFramesAndOtherUserRemainsConnected() throws Exception {
        Fixture other = fixture("other@example.invalid", "other-socket");
        staff.user().setActive(false);
        sessions.recheckSessions();
        verify(staff.socket()).close(CloseStatus.POLICY_VIOLATION);
        assertTrue(sessions.isCurrent(other.socket().getId()));
        verify(other.socket(), never()).close(any());
        Scheduled schedule = StaffWebSocketSessions.class.getMethod("recheckSessions").getAnnotation(Scheduled.class);
        assertEquals(30_000, schedule.fixedDelay());
        assertEquals("staffWebSocketRecheckScheduler", schedule.scheduler());
        assertNotNull(WebSocketConfig.class.getAnnotation(EnableScheduling.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "invalidated", "expired", "password-baseline", "permission-baseline", "different-login"})
    void logoutAndExpiredOrReplacedHttpSessionsCloseExistingSockets(String change) throws Exception {
        switch (change) {
            case "logout" -> staff.httpSession().removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            case "invalidated" -> staff.httpSession().invalidate();
            case "expired" -> {
                staff.httpSession().setMaxInactiveInterval(1);
                doReturn(System.currentTimeMillis() - 2_000).when(staff.httpSession()).getLastAccessedTime();
            }
            case "password-baseline" -> staff.httpSession().removeAttribute("accountPasswordVersion");
            case "permission-baseline" -> staff.httpSession().setAttribute("permissionHash", "changed");
            case "different-login" -> {
                var replacement = SecurityContextHolder.createEmptyContext();
                replacement.setAuthentication(new UsernamePasswordAuthenticationToken("other@example.invalid", null,
                        List.of(new SimpleGrantedAuthority("ROLE_TRAVEL_CONSULTANT"))));
                staff.httpSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, replacement);
            }
            default -> fail("Unknown fixture mutation");
        }
        sessions.recheckSessions();
        assertFalse(sessions.isCurrent(staff.socket().getId()));
        verify(staff.socket()).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void repositoryFailureFailsClosedInsteadOfKeepingOldAuthoritySnapshot() throws Exception {
        when(users.findByEmailIgnoreCase(staff.user().getEmail())).thenThrow(new IllegalStateException("synthetic unavailable store"));
        assertNull(sessions.outboundAuthorization().preSend(event(staff.socket().getId()), null));
        verify(staff.socket()).close(CloseStatus.POLICY_VIOLATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalidate-http-session", "revoke-socket"})
    void revocationDuringAccountLookupCannotAuthorizeAnOtherwiseUnchangedAccount(String change) throws Exception {
        when(users.findByEmailIgnoreCase(staff.user().getEmail())).thenAnswer(invocation -> {
            if ("invalidate-http-session".equals(change)) staff.httpSession().invalidate();
            else sessions.revoke(staff.socket().getId());
            return Optional.of(staff.user());
        });
        assertNull(sessions.outboundAuthorization().preSend(event(staff.socket().getId()), null));
        assertFalse(sessions.isCurrent(staff.socket().getId()));
        verify(staff.socket(), times(1)).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void handshakeAndEstablishedSocketRequireTrustedHttpBaselines() throws Exception {
        var request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        assertFalse(sessions.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class), new HashMap<>()));
        var socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getPrincipal()).thenReturn(staff.authentication());
        when(socket.getAttributes()).thenReturn(Map.of());
        var delegate = mock(WebSocketHandler.class);
        sessions.decorate(delegate).afterConnectionEstablished(socket);
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(delegate);
        assertNull(sessions.outboundAuthorization().preSend(event("unknown-socket"), null));
        assertFalse(sessions.isCurrent(null));
    }

    @Test
    void canonicalLegacyConsultantRoleWorksButCustomerOrMalformedRolesNeverDo() {
        staff.user().getRole().setRoleName("PACKAGE_MANAGER");
        assertTrue(sessions.isCurrent(staff.socket().getId()));
        staff.user().getRole().setRoleName(null);
        assertFalse(sessions.isCurrent(staff.socket().getId()));
    }

    @Test
    void disablingDemoAccessClosesAnExistingLocalDemoSocket() {
        staff.user().setDemoAccount(true);
        when(demoMode.isEnabled()).thenReturn(true);
        assertTrue(sessions.isCurrent(staff.socket().getId()));
        when(demoMode.isEnabled()).thenReturn(false);
        assertFalse(sessions.isCurrent(staff.socket().getId()));
    }

    @Test
    void transportCloseRemovesTheRegistration() throws Exception {
        var delegate = mock(WebSocketHandler.class);
        sessions.decorate(delegate).afterConnectionClosed(staff.socket(), CloseStatus.NORMAL);
        assertFalse(sessions.isCurrent(staff.socket().getId()));
        verify(delegate).afterConnectionClosed(staff.socket(), CloseStatus.NORMAL);
    }

    @Test
    void transportDisconnectStillCleansBrokerSubscriptionsAfterRevocation() throws Exception {
        var authorization = new StaffWebSocketAuthorization(sessions);
        var connect = control(StompCommand.CONNECT);
        assertSame(connect, authorization.preSend(connect, null));
        staff.user().setActive(false);
        sessions.recheckSessions();
        var disconnect = control(StompCommand.DISCONNECT);
        assertSame(disconnect, authorization.preSend(disconnect, null));
        assertSame(disconnect, authorization.beforeHandle(disconnect, null, null));
        assertFalse(sessions.isCurrent(staff.socket().getId()));
        assertNull(sessions.outboundAuthorization().preSend(event(staff.socket().getId()), null));
        verify(staff.socket()).close(CloseStatus.POLICY_VIOLATION);
    }

    private Fixture fixture(String email, String id) throws Exception {
        Role role = new Role("TRAVEL_CONSULTANT", "Synthetic staff role");
        role.setPermissions(new HashSet<>(List.of(permission(11L, "BOOKING_VIEW"))));
        User user = new User("Synthetic staff", email, "synthetic-password-hash", role);
        when(users.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
        Authentication authentication = new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVEL_CONSULTANT"), new SimpleGrantedAuthority("PERM_BOOKING_VIEW"),
                        FactorGrantedAuthority.fromAuthority("FACTOR_PASSWORD")));
        var httpSession = spy(new MockHttpSession());
        httpSession.setAttribute("accountPasswordVersion", user.getPassword());
        httpSession.setAttribute("permissionHash", CustomUserDetailsService.permissionHash(role.getPermissions()));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        httpSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        var request = new MockHttpServletRequest();
        request.setSession(httpSession);
        var attributes = new HashMap<String, Object>();
        assertTrue(sessions.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class), attributes));
        WebSocketSession socket = mock(WebSocketSession.class);
        AtomicBoolean open = new AtomicBoolean(true);
        when(socket.getId()).thenReturn(id);
        when(socket.getPrincipal()).thenReturn(authentication);
        when(socket.getAttributes()).thenReturn(attributes);
        when(socket.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 54321));
        when(socket.isOpen()).thenAnswer(invocation -> open.get());
        doAnswer(invocation -> { open.set(false); return null; }).when(socket).close(any(CloseStatus.class));
        var delegate = mock(WebSocketHandler.class);
        sessions.decorate(delegate).afterConnectionEstablished(socket);
        verify(delegate).afterConnectionEstablished(socket);
        return new Fixture(socket, httpSession, authentication, user);
    }

    private static Permission permission(long id, String name) {
        Permission permission = new Permission(name, "Synthetic permission", "BOOKING");
        permission.setId(id);
        return permission;
    }

    private Message<?> event(String sessionId) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setDestination("/topic/staff-updates");
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private Message<?> control(StompCommand command) {
        var headers = StompHeaderAccessor.create(command);
        headers.setSessionId(staff.socket().getId());
        headers.setSessionAttributes(staff.socket().getAttributes());
        headers.setUser(staff.authentication());
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private record Fixture(WebSocketSession socket, MockHttpSession httpSession, Authentication authentication, User user) { }
}
