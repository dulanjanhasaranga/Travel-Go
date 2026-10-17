package com.travelgo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after CSRF validation and before password authentication/controller execution. */
public final class AuthenticationAbuseFilter extends OncePerRequestFilter {
    private static final Map<PathPatternRequestMatcher, AuthenticationRequestLimiter.RequestKind> ROUTES = Map.of(
            post("/auth/login"), AuthenticationRequestLimiter.RequestKind.LOGIN,
            post("/auth/register"), AuthenticationRequestLimiter.RequestKind.REGISTRATION,
            post("/auth/forgot-password"), AuthenticationRequestLimiter.RequestKind.EMAIL_REQUEST,
            post("/auth/resend-verification"), AuthenticationRequestLimiter.RequestKind.EMAIL_REQUEST,
            post("/auth/verify-email"), AuthenticationRequestLimiter.RequestKind.TOKEN_SUBMISSION,
            post("/auth/reset-password"), AuthenticationRequestLimiter.RequestKind.TOKEN_SUBMISSION);
    private final AuthenticationRequestLimiter limiter;

    public AuthenticationAbuseFilter(AuthenticationRequestLimiter limiter) { this.limiter = limiter; }

    private static PathPatternRequestMatcher post(String path) {
        return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, path);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        var kind = ROUTES.entrySet().stream().filter(route -> route.getKey().matches(request))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        if (kind == null) { chain.doFilter(request, response); return; }
        // Client-supplied X-Forwarded-For/Forwarded/X-Real-IP headers are never read here.
        var decision = limiter.acquire(kind, request.getRemoteAddr(), request.getParameter("email"));
        if (decision.allowed()) { chain.doFilter(request, response); return; }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setContentType("text/html");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Static content only: no credential, token, email or address is reflected or logged.
        response.getWriter().write("""
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="robots" content="noindex"><title>Please wait | TravelGO</title>
                <style>body{margin:0;background:#f2f6fc;color:#173b67;font:16px/1.6 system-ui,sans-serif}
                main{max-width:32rem;margin:12vh auto;padding:2rem;background:white;border:1px solid #d3dde9;
                border-radius:1rem}h1{font-size:1.6rem;line-height:1.25}p{color:#52647f}
                a{display:inline-block;color:#173b67;font-weight:600;text-underline-offset:.2em}
                @media(max-width:600px){main{margin:10vh 1rem}}</style></head>
                <body><main><p>TravelGO account access</p><h1>Please wait before trying again</h1>
                <p>Too many account requests were received from this connection. Please wait 
                """ + decision.retryAfterSeconds() + """
                 seconds, then try again. If you need help, contact the agency.</p>
                <a href="/auth/login">Return to sign in</a></main></body></html>
                """);
    }
}
