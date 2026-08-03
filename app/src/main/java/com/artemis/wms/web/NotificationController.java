package com.artemis.wms.web;

import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The bell. Every endpoint scoped to the authenticated user — no capability needed to read your own bell. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("unread", notifications.unreadCount(TenantContext.user()));
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                          @RequestParam(defaultValue = "50") int limit) {
        return notifications.list(TenantContext.user(), unreadOnly, Math.min(limit, 200));
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> read(@PathVariable UUID id) {
        notifications.markRead(TenantContext.user(), id);
        return Map.of("notificationId", id, "read", true);
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll() {
        return Map.of("marked", notifications.markAllRead(TenantContext.user()));
    }
}
