package com.travelgo.config;

import java.util.Set;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/** Clients may consume the staff event feed; only application services can publish events. */
public final class StaffWebSocketAuthorization implements ExecutorChannelInterceptor {
    private static final Set<String> STAFF_ROLES = Set.of("ROLE_ADMIN", "ROLE_TRAVEL_CONSULTANT", "ROLE_VISA_OFFICER");
    private static final String CONNECTED = StaffWebSocketAuthorization.class.getName() + ".connected";
    private final StaffWebSocketSessions sessions;

    public StaffWebSocketAuthorization(StaffWebSocketSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = StompHeaderAccessor.wrap(message);
        SimpMessageType type = headers.getMessageType();
        var attributes = headers.getSessionAttributes();
        // Transport closure also emits DISCONNECT. Let validated sessions release broker subscriptions
        // after access has been revoked; this grants no subscription or publishing access.
        if (type == SimpMessageType.DISCONNECT && headers.getDestination() == null && attributes != null
                && Boolean.TRUE.equals(attributes.get(CONNECTED))) {
            attributes.remove(CONNECTED);
            return message;
        }
        if (!sessions.isCurrent(headers.getSessionId())) {
            throw new AccessDeniedException("Live update access has expired. Sign in again.");
        }
        if (!(headers.getUser() instanceof Authentication authentication) || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(authority -> STAFF_ROLES.contains(authority.getAuthority()))) {
            throw new AccessDeniedException("Staff access is required for live updates.");
        }
        if (attributes == null) throw new AccessDeniedException("A live update session is required.");
        // Registered after the CSRF interceptor, so only a validated CONNECT can establish this state.
        if (type == SimpMessageType.CONNECT && headers.getDestination() == null) {
            attributes.put(CONNECTED, Boolean.TRUE);
            return message;
        }
        if (type == SimpMessageType.DISCONNECT && headers.getDestination() == null) {
            attributes.remove(CONNECTED);
            return message;
        }
        if (Boolean.TRUE.equals(attributes.get(CONNECTED))) {
            if (type == SimpMessageType.SUBSCRIBE && "/topic/staff-updates".equals(headers.getDestination())) return message;
            if ((type == SimpMessageType.UNSUBSCRIBE || type == SimpMessageType.HEARTBEAT)
                    && headers.getDestination() == null) return message;
        }
        throw new AccessDeniedException("This live update action is unavailable.");
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        var headers = StompHeaderAccessor.wrap(message);
        if (headers.getMessageType() == SimpMessageType.DISCONNECT && headers.getDestination() == null) return message;
        // Recheck after executor queuing as well, so a queued frame cannot retain revoked access.
        return sessions.isCurrent(headers.getSessionId()) ? message : null;
    }
}
