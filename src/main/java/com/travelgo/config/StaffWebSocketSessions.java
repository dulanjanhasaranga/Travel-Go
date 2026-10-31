package com.travelgo.config;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Tracks native sockets and closes them when their HTTP session or current account access expires. */
@Component
public class StaffWebSocketSessions implements HandshakeInterceptor {
    private static final String HTTP_SESSION = StaffWebSocketSessions.class.getName() + ".httpSession";
    private static final String PASSWORD_BASELINE = "accountPasswordVersion";
    private static final String PERMISSION_BASELINE = "permissionHash";
    private final StaffWebSocketAccountAccess accountAccess;
    private com.travelgo.service.StaffMfaService staffMfa;
    private final Map<String, ConnectedSession> sessions = new ConcurrentHashMap<>();

    public StaffWebSocketSessions(StaffWebSocketAccountAccess accountAccess) {
        this.accountAccess = accountAccess;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void staffMfa(com.travelgo.service.StaffMfaService service) { this.staffMfa = service; }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) return false;
        HttpSession session = servletRequest.getServletRequest().getSession(false);
        try {
            if (session == null || !(session.getAttribute(PASSWORD_BASELINE) instanceof String password)
                    || password.isBlank() || !(session.getAttribute(PERMISSION_BASELINE) instanceof String permissions)) return false;
            // Keep only the required baselines and a server-side session reference, never client-supplied headers.
            attributes.put(HTTP_SESSION, session);
            attributes.put(PASSWORD_BASELINE, password);
            attributes.put(PERMISSION_BASELINE, permissions);
            return true;
        } catch (IllegalStateException expired) {
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Exception exception) { }

    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                if (!register(session)) return;
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception failure) {
                    revoke(session.getId());
                    throw failure;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    private boolean register(WebSocketSession socket) {
        Map<String, Object> attributes = socket.getAttributes();
        if (!(socket.getPrincipal() instanceof Authentication authentication)
                || !(attributes.get(HTTP_SESSION) instanceof HttpSession httpSession)
                || !(attributes.get(PASSWORD_BASELINE) instanceof String password)
                || !(attributes.get(PERMISSION_BASELINE) instanceof String permissions)) {
            close(socket);
            return false;
        }
        var remote = socket.getRemoteAddress();
        boolean local = remote != null && remote.getAddress() != null && remote.getAddress().isLoopbackAddress();
        sessions.put(socket.getId(), new ConnectedSession(socket, httpSession, authentication, password, permissions, local));
        return isCurrent(socket.getId());
    }

    public boolean isCurrent(String sessionId) {
        if (sessionId == null) return false;
        ConnectedSession session = sessions.get(sessionId);
        if (session == null) return false;
        try {
            if (session.socket().isOpen() && httpSessionCurrent(session)
                    && accountAccess.isCurrent(session.authentication(), session.password(), session.permissions(), session.local())
                    // Logout or transport revocation may complete while the database lookup is in progress.
                    && sessions.get(sessionId) == session && session.socket().isOpen() && httpSessionCurrent(session)) return true;
        } catch (RuntimeException unavailableOrExpired) {
            // An unavailable account store, expired HTTP session or failed transaction never retains access.
        }
        revoke(sessionId);
        return false;
    }

    private boolean httpSessionCurrent(ConnectedSession connected) {
        HttpSession session = connected.httpSession();
        int idleSeconds = session.getMaxInactiveInterval();
        if (idleSeconds > 0 && System.currentTimeMillis() - session.getLastAccessedTime() >= idleSeconds * 1000L) return false;
        if (!Objects.equals(connected.password(), session.getAttribute(PASSWORD_BASELINE))
                || !Objects.equals(connected.permissions(), session.getAttribute(PERMISSION_BASELINE))) return false;
        Object saved = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(saved instanceof SecurityContext context)) return false;
        Authentication current = context.getAuthentication();
        return current != null && current.isAuthenticated()
                && connected.authentication().getName().equals(current.getName())
                && connected.authentication().getAuthorities().equals(current.getAuthorities())
                && (staffMfa == null || mfaCurrent(current, session));
    }

    private boolean mfaCurrent(Authentication authentication, HttpSession session) {
        var status = staffMfa.status(authentication, session);
        return !status.required() || status.verified();
    }

    public void revoke(String sessionId) {
        if (sessionId == null) return;
        ConnectedSession removed = sessions.remove(sessionId);
        if (removed != null) close(removed.socket());
    }

    private void close(WebSocketSession socket) {
        try {
            if (socket.isOpen()) socket.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {
            // Removed sessions cannot deliver again even if the transport is already failing.
        }
    }

    public ChannelInterceptor outboundAuthorization() {
        return new ExecutorChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, org.springframework.messaging.MessageChannel channel) {
                String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
                return isCurrent(sessionId) ? message : null;
            }

            @Override
            public Message<?> beforeHandle(Message<?> message, org.springframework.messaging.MessageChannel channel,
                    org.springframework.messaging.MessageHandler handler) {
                // Outbound frames may wait in an executor after preSend checked the account.
                return preSend(message, channel);
            }
        };
    }

    /** Idle subscriptions are also checked; message delivery always performs its own fresh check. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000, scheduler = "staffWebSocketRecheckScheduler")
    public void recheckSessions() {
        sessions.keySet().forEach(this::isCurrent);
    }

    private record ConnectedSession(WebSocketSession socket, HttpSession httpSession, Authentication authentication,
            String password, String permissions, boolean local) { }
}
