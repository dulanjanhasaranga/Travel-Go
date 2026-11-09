package com.travelgo.config;

import com.travelgo.service.RequestPageLayout;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Interceptor that checks website maintenance status.
 * If the site is in MAINTENANCE mode:
 * - Public users are redirected to the maintenance page
 * - Admin users can still access admin pages
 * - Static resources and auth pages are always accessible
 */
@Component
public class MaintenanceInterceptor implements HandlerInterceptor {

    private final RequestPageLayout layout;

    public MaintenanceInterceptor(RequestPageLayout layout) {
        this.layout = layout;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String uri = request.getRequestURI();

        // Always allow: static resources, auth pages, admin pages, maintenance page itself
        if (uri.startsWith("/css") || uri.startsWith("/js") || uri.startsWith("/images") ||
            uri.startsWith("/static") || uri.startsWith("/auth") || uri.startsWith("/admin") ||
            uri.equals("/maintenance")) {
            return true;
        }

        // Check if maintenance mode is active
        if ("MAINTENANCE".equals(layout.get().settings().getWebsiteStatus())) {
            response.sendRedirect("/maintenance");
            return false;
        }

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) throws Exception {
        // Add system settings to all page models for footer/navbar display
        if (modelAndView != null && !request.getRequestURI().startsWith("/css")
                && !request.getRequestURI().startsWith("/js")) {
            try {
                modelAndView.addObject("systemSettings", layout.get().settings());
            } catch (Exception e) {
                // Silently ignore if settings are not yet initialized
            }
        }
    }
}
