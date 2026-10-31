package com.travelgo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.web.filter.OncePerRequestFilter;

/** Grants script execution only to explicitly authored, nonce-bearing template elements. */
public final class ContentSecurityPolicyFilter extends OncePerRequestFilter {
    public static final String NONCE_ATTRIBUTE = ContentSecurityPolicyFilter.class.getName() + ".nonce";
    private final SecureRandom random = new SecureRandom();
    private final boolean enabled;
    private final String websocketSources;

    public ContentSecurityPolicyFilter(boolean enabled, String publicBaseUrl) {
        this.enabled = enabled;
        URI origin;
        try { origin = URI.create(publicBaseUrl); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid CSP public origin."); }
        if (origin.getHost() == null || !origin.getHost().matches("[A-Za-z0-9.\\[\\]:-]+")
                || !("https".equals(origin.getScheme()) || "http".equals(origin.getScheme()))
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                || (origin.getRawPath() != null && !origin.getRawPath().isEmpty() && !"/".equals(origin.getRawPath()))
                || origin.getPort() == 0 || origin.getPort() > 65535)
            throw new IllegalArgumentException("Invalid CSP public origin.");
        String host = origin.getHost();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        String port = origin.getPort() < 0 ? "" : ":" + origin.getPort();
        String source = ("https".equals(origin.getScheme()) ? "wss://" : "ws://") + host + port;
        // Local browser URLs use either loopback spelling. Never trust a client Host/forwarded header.
        if ("http".equals(origin.getScheme()) && ("localhost".equals(host) || "127.0.0.1".equals(host)))
            source = "ws://localhost" + port + " ws://127.0.0.1" + port;
        this.websocketSources = source;
    }

    public boolean enabled() { return enabled; }

    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
                                                        FilterChain chain) throws ServletException, IOException {
        if (enabled && request.getAttribute(NONCE_ATTRIBUTE) instanceof String nonce)
            response.setHeader("Content-Security-Policy", policy(nonce));
        chain.doFilter(request, response);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        if (enabled) {
            String nonce;
            if (request.getDispatcherType() == DispatcherType.ERROR
                    && request.getAttribute(NONCE_ATTRIBUTE) instanceof String original) nonce = original;
            else {
                byte[] value = new byte[32];
                random.nextBytes(value);
                nonce = Base64.getEncoder().withoutPadding().encodeToString(value);
            }
            request.setAttribute(NONCE_ATTRIBUTE, nonce);
            response.setHeader("Content-Security-Policy", policy(nonce));
        }
        chain.doFilter(request, response);
    }

    private String policy(String nonce) {
        return "default-src 'self'; script-src 'nonce-" + nonce + "'; script-src-attr 'none'; "
                + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                + "font-src 'self' https://fonts.gstatic.com; img-src 'self' https: data:; "
                + "connect-src 'self' " + websocketSources + "; "
                + "frame-src https://maps.google.com https://www.google.com; "
                // Browsers can apply form-action to checkout redirects as well as the initial POST.
                + "form-action 'self' https://sandbox.payhere.lk https://www.payhere.lk https://checkout.stripe.com; "
                + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'";
    }
}
