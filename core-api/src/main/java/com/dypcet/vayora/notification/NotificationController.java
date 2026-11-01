package com.dypcet.vayora.notification;

import com.dypcet.vayora.identity.CurrentUser;
import com.dypcet.vayora.notification.NotificationService.Notification;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<Notification> mine(@AuthenticationPrincipal Jwt jwt) {
        return notifications.forUser(CurrentUser.id(jwt));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("unread", notifications.unreadCount(CurrentUser.id(jwt)));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        if (!notifications.markRead(CurrentUser.id(jwt), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
    }

    @GetMapping("/preferences")
    public NotificationService.Preferences preferences(@AuthenticationPrincipal Jwt jwt) {
        return notifications.preferences(CurrentUser.id(jwt));
    }

    @PutMapping("/preferences")
    public NotificationService.Preferences setPreferences(@AuthenticationPrincipal Jwt jwt,
            @RequestBody NotificationService.Preferences preferences) {
        return notifications.setPreferences(CurrentUser.id(jwt), preferences);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        notifications.markAllRead(CurrentUser.id(jwt));
    }
}
