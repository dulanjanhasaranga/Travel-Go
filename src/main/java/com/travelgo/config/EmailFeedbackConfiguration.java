package com.travelgo.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Optional provider adapter: no account, sender, webhook or network action is created here. */
@Component
public class EmailFeedbackConfiguration {
    private final boolean enabled;
    private final byte[] expectedAuthorization;

    public EmailFeedbackConfiguration(
            @Value("${travelgo.mail.feedback.brevo-enabled:false}") boolean enabled,
            @Value("${travelgo.mail.feedback.brevo-token:}") String token,
            @Value("${travelgo.public-base-url:http://localhost:8081}") String publicOrigin) {
        this.enabled = enabled;
        if (enabled) {
            if (!token.matches("[A-Za-z0-9_-]{32,128}")
                    || token.toLowerCase(java.util.Locale.ROOT).contains("placeholder")
                    || token.toLowerCase(java.util.Locale.ROOT).contains("replace"))
                throw new IllegalStateException("Email feedback requires a private, randomly generated webhook token.");
            try {
                URI uri = URI.create(publicOrigin);
                if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                        || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                            && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost())))))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("Email feedback requires an HTTPS public origin; loopback HTTP is allowed for isolated tests.");
            }
        }
        expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    public void authenticate(String authorization) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (authorization == null || authorization.length() > 150
                || !MessageDigest.isEqual(expectedAuthorization, authorization.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook authentication required.");
    }
}
