package com.travelgo.controller;

import com.travelgo.service.NotificationService;
import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class NotificationModelAdvice {
    private final NotificationService notifications;
    public NotificationModelAdvice(NotificationService notifications) { this.notifications = notifications; }

    @ModelAttribute("notificationSummary")
    public NotificationService.Summary summary(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken)
            return new NotificationService.Summary(0, List.of());
        return notifications.summary();
    }
}
