package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.MetricsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dashboards are ADMIN-only (DASHBOARD_VIEW); labor detail needs METRICS_VIEW_ALL; /labor/self is any user. */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final MetricsService metrics;
    private final CapabilityService caps;

    public MetricsController(MetricsService metrics, CapabilityService caps) {
        this.metrics = metrics; this.caps = caps;
    }

    @GetMapping("/pick-face-velocity")
    public List<Map<String, Object>> velocity(@RequestParam UUID siteId) {
        caps.require(TenantContext.user(), siteId, Capabilities.DASHBOARD_VIEW);
        return metrics.pickFaceVelocity(siteId);
    }

    @GetMapping("/receiving-progress")
    public List<Map<String, Object>> receiving(@RequestParam UUID siteId) {
        caps.require(TenantContext.user(), siteId, Capabilities.DASHBOARD_VIEW);
        return metrics.receivingProgress(siteId);
    }

    @GetMapping("/shipping-progress")
    public List<Map<String, Object>> shipping(@RequestParam UUID siteId) {
        caps.require(TenantContext.user(), siteId, Capabilities.DASHBOARD_VIEW);
        return metrics.shippingProgress(siteId);
    }

    @GetMapping("/wave-progress")
    public List<Map<String, Object>> waves(@RequestParam UUID siteId) {
        caps.require(TenantContext.user(), siteId, Capabilities.DASHBOARD_VIEW);
        return metrics.waveProgress(siteId);
    }

    @GetMapping("/replen-pressure")
    public List<Map<String, Object>> replen(@RequestParam UUID siteId) {
        caps.require(TenantContext.user(), siteId, Capabilities.DASHBOARD_VIEW);
        return metrics.replenPressure(siteId);
    }

    @GetMapping("/labor")
    public List<Map<String, Object>> labor(@RequestParam UUID siteId) {
        caps.require(TenantContext.user(), siteId, Capabilities.METRICS_VIEW_ALL);
        return metrics.laborProductivity(siteId);
    }

    @GetMapping("/labor/self")
    public List<Map<String, Object>> laborSelf(@RequestParam UUID siteId) {
        caps.require(TenantContext.user(), null, Capabilities.METRICS_VIEW_SELF);
        return metrics.laborSelf(siteId, TenantContext.user());
    }

    @GetMapping("/expiry-risk")
    public List<Map<String, Object>> expiry(@RequestParam UUID siteId) {
        caps.require(TenantContext.user(), siteId, Capabilities.DASHBOARD_VIEW);
        return metrics.expiryRisk(siteId);
    }
}
