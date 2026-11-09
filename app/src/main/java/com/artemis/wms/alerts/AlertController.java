package com.artemis.wms.alerts;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AlertController {
    private final AlertService alerts;
    public AlertController(AlertService alerts) { this.alerts = alerts; }

    @GetMapping("/sites/{siteId}/alerts") public List<Map<String, Object>> open(@PathVariable UUID siteId) { return alerts.open(siteId); }
    @PostMapping("/alerts/{id}/acknowledge") public Map<String, Object> ack(@PathVariable UUID id) { alerts.acknowledge(id); return Map.of("alertId", id, "acknowledged", true); }

    @GetMapping("/notifications/count") public Map<String, Object> count() { return Map.of("unread", alerts.unreadCount()); }
    @GetMapping("/notifications") public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean unread) { return alerts.notifications(unread); }
    @PostMapping("/notifications/{id}/read") public Map<String, Object> read(@PathVariable UUID id) { alerts.markRead(id); return Map.of("read", true); }
    @PostMapping("/notifications/read-all") public Map<String, Object> readAll() { alerts.markAllRead(); return Map.of("read", true); }
}
