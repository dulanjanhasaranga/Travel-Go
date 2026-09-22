package com.travelgo.controller;

import com.travelgo.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * Shared authenticated inbox; the original customer URL remains available.
 */
@Controller
public class CustomerNotificationController {
    private final NotificationService notifications;
    public CustomerNotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping({"/notifications", "/customer/notifications"})
    public String inbox(@RequestParam(defaultValue = "all") String category,
                        @RequestParam(defaultValue = "false") boolean unread,
                        @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("notificationPage", notifications.inbox(category, unread, page));
        model.addAttribute("category", category);
        model.addAttribute("unreadOnly", unread);
        model.addAttribute("currentUser", notifications.currentUser());
        return "customer/notifications";
    }

    @GetMapping("/notifications/feed")
    @ResponseBody
    public ResponseEntity<NotificationService.Summary> feed() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.summary());
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseBody
    public ResponseEntity<?> read(@PathVariable Long id, @RequestParam(defaultValue = "open") String destination,
                                  HttpServletRequest request) {
        var result = notifications.markRead(id);
        if (acceptsJson(request)) return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        return ResponseEntity.status(303).location(URI.create(destination.equals("stay") ? "/notifications" : result.url())).build();
    }

    @PostMapping("/notifications/read-all")
    @ResponseBody
    public ResponseEntity<?> readAll(HttpServletRequest request) {
        long unreadCount = notifications.markAllRead();
        if (acceptsJson(request)) return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("unreadCount", unreadCount));
        return ResponseEntity.status(303).location(URI.create("/notifications")).build();
    }

    private boolean acceptsJson(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("application/json");
    }
}
