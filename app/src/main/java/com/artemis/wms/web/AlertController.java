package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.AlertService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alerts;
    private final CapabilityService caps;

    public AlertController(AlertService alerts, CapabilityService caps) { this.alerts = alerts; this.caps = caps; }

    @GetMapping
    public List<Map<String, Object>> open(@RequestParam UUID siteId, @RequestParam(required = false) UUID areaId) {
        caps.require(TenantContext.user(), siteId, Capabilities.DASHBOARD_VIEW);
        return alerts.open(siteId, areaId);
    }

    @PostMapping("/{id}/acknowledge")
    public Map<String, Object> acknowledge(@PathVariable UUID id) {
        caps.require(TenantContext.user(), null, Capabilities.DASHBOARD_VIEW);
        alerts.acknowledge(id);
        return Map.of("alertId", id, "acknowledged", true);
    }
}
