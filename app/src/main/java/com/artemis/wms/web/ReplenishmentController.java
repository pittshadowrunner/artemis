package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.ReplenishmentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.service.LocationService.str;

@RestController
@RequestMapping("/api/v1/replenishment")
public class ReplenishmentController {

    private final ReplenishmentService replen;
    private final CapabilityService caps;

    public ReplenishmentController(ReplenishmentService replen, CapabilityService caps) {
        this.replen = replen; this.caps = caps;
    }

    /** On-demand trigger scan (the scheduler also runs it every minute). */
    @PostMapping("/scan")
    public Map<String, Object> scan(@RequestParam UUID siteId) {
        caps.require(TenantContext.user(), siteId, Capabilities.REPLEN_EXECUTE);
        return replen.scan(siteId);
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks(@RequestParam UUID siteId) {
        caps.require(TenantContext.user(), siteId, Capabilities.REPLEN_EXECUTE);
        return replen.openTasks(siteId);
    }

    @PostMapping("/tasks/{taskId}/complete")
    public Map<String, Object> complete(@PathVariable UUID taskId, @RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.REPLEN_EXECUTE);
        replen.complete(taskId, str(body.get("checkDigits")), str(body.get("putCheckDigits")));
        return Map.of("taskId", taskId, "status", "COMPLETE");
    }
}
