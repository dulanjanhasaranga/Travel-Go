package com.travelgo.config;

import com.travelgo.service.StaffMfaService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** A password session cannot reach business pages, APIs, documents or sockets until its second factor passes. */
@Component
public class StaffMfaGateFilter extends OncePerRequestFilter {
    private static final Set<String> MFA_PATHS = Set.of("/auth/mfa", "/auth/mfa/enroll", "/auth/mfa/confirm",
            "/auth/mfa/verify", "/auth/mfa/reauthenticate", "/auth/mfa/recovery-codes/regenerate", "/auth/mfa/authenticator/replace");
    private final StaffMfaService mfa;
    public StaffMfaGateFilter(StaffMfaService mfa) { this.mfa = mfa; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!mfa.isEnabled() || !mfa.isStaff(SecurityContextHolder.getContext().getAuthentication())
                || staticResource(request, path) || "/healthz".equals(path) || "/error".equals(path)
                || ("POST".equals(request.getMethod()) && "/auth/logout".equals(path)) || MFA_PATHS.contains(path)) {
            chain.doFilter(request, response); return;
        }
        StaffMfaService.Status status;
        try { status = mfa.status(SecurityContextHolder.getContext().getAuthentication(), request.getSession(false)); }
        catch (org.springframework.security.access.AccessDeniedException stale) {
            SecurityContextHolder.clearContext(); HttpSession session = request.getSession(false);
            if (session != null) session.invalidate(); response.sendRedirect("/auth/login?sessionChanged=true"); return;
        } catch (RuntimeException unavailable) {
            response.setStatus(503); noStore(response); response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Staff verification is temporarily unavailable. Please try again later."); return;
        }
        boolean stepUp = status.required() && sensitiveMutation(request, path) && !status.fresh();
        if (status.required() && (!status.verified() || stepUp)) {
            noStore(response);
            if (path.equals("/ws-travelgo") || path.startsWith("/ws-travelgo/") || path.startsWith("/api/")) {
                response.setStatus(403); response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Complete staff verification to continue."); return;
            }
            response.setStatus(303);
            response.setHeader("Location", stepUp ? "/auth/mfa?stepUp=true" : "/auth/mfa"); return;
        }
        chain.doFilter(request, response);
    }
    private boolean sensitiveMutation(HttpServletRequest request, String path) {
        return !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                && (path.equals("/admin") || path.startsWith("/admin/")
                    || path.equals("/staff/payments") || path.startsWith("/staff/payments/"));
    }
    private boolean staticResource(HttpServletRequest request, String path) {
        return ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && Set.of("/css/", "/js/", "/images/", "/vendor/", "/video/").stream().anyMatch(path::startsWith);
    }
    public static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0"); response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
