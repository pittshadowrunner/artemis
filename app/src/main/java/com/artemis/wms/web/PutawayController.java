package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.PutawayService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.service.LocationService.str;
import static com.artemis.wms.service.LocationService.uuid;

@RestController
@RequestMapping("/api/v1/putaway/tasks")
public class PutawayController {

    private final PutawayService putaway;
    private final CapabilityService caps;

    public PutawayController(PutawayService putaway, CapabilityService caps) {
        this.putaway = putaway; this.caps = caps;
    }

    @GetMapping
    public List<Map<String, Object>> open(@RequestParam UUID siteId) {
        caps.require(TenantContext.user(), siteId, Capabilities.PUTAWAY_EXECUTE);
        return putaway.openTasks(siteId);
    }

    @PostMapping("/{taskId}/complete")
    public Map<String, Object> complete(@PathVariable UUID taskId, @RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.PUTAWAY_EXECUTE);
        putaway.complete(taskId, str(body.get("checkDigits")),
                uuid(body.get("overrideLocationId")), str(body.get("overrideReason")));
        return Map.of("taskId", taskId, "status", "COMPLETE");
    }
}
