package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.SelectionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.service.LocationService.str;

@RestController
@RequestMapping("/api/v1/selection")
public class SelectionController {

    private final SelectionService selection;
    private final CapabilityService caps;

    public SelectionController(SelectionService selection, CapabilityService caps) {
        this.selection = selection; this.caps = caps;
    }

    @GetMapping("/assignments/{id}/tasks")
    public List<Map<String, Object>> tasks(@PathVariable UUID id) {
        caps.require(TenantContext.user(), null, Capabilities.SELECTION_EXECUTE);
        return selection.tasks(id);
    }

    @PostMapping("/assignments/{id}/induct")
    public Map<String, Object> induct(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.SELECTION_EXECUTE);
        selection.induct(id, UUID.fromString(body.get("orderId").toString()), str(body.get("lpn")));
        return Map.of("assignmentId", id, "inducted", str(body.get("lpn")));
    }

    @PostMapping("/tasks/{taskId}/pick")
    public Map<String, Object> pick(@PathVariable UUID taskId, @RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.SELECTION_EXECUTE);
        return selection.pick(taskId, body);
    }

    @PostMapping("/drops")
    public Map<String, Object> drop(@RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.SELECTION_EXECUTE);
        return selection.drop(UUID.fromString(body.get("orderId").toString()), str(body.get("dropLocation")));
    }
}
