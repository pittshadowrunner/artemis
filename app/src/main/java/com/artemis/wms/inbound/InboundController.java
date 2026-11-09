package com.artemis.wms.inbound;

import com.artemis.wms.work.DispatchService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.platform.Values.*;

@RestController
@RequestMapping("/api/v1")
public class InboundController {
    private final ReceivingService receiving;
    private final PutawayService putaway;
    private final DispatchService dispatch;
    public InboundController(ReceivingService receiving, PutawayService putaway, DispatchService dispatch) { this.receiving = receiving; this.putaway = putaway; this.dispatch = dispatch; }

    // ---- receiving
    @GetMapping("/sites/{siteId}/receiving")
    public List<Map<String, Object>> manifests(@PathVariable UUID siteId, @RequestParam(defaultValue = "true") boolean open) { return receiving.list(siteId, open); }

    /** {manifestNumber, carrier?, trailer?, expectedDate?, lines:[{sku, expectedQty}]} */
    @PostMapping("/sites/{siteId}/receiving") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable UUID siteId, @RequestBody Map<String, Object> body) { return Map.of("manifestId", receiving.createManifest(siteId, body)); }

    @GetMapping("/receiving/{id}") public Map<String, Object> manifest(@PathVariable UUID id) { return receiving.get(id); }
    @PostMapping("/receiving/{id}/arrive") public Map<String, Object> arrive(@PathVariable UUID id) { receiving.arrive(id); return receiving.get(id); }
    @PostMapping("/receiving/{id}/close")  public Map<String, Object> close(@PathVariable UUID id)  { receiving.close(id);  return receiving.get(id); }

    /** {lineNumber, lpn, qty, lot?, expirationDate?, serials?, actualWeightKg?, dock?, skipPutaway?} */
    @PostMapping("/receiving/{id}/pallets") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> receive(@PathVariable UUID id, @RequestBody Map<String, Object> body) { return receiving.receive(id, body); }

    /** {lineNumber, qty, reason} */
    @PostMapping("/receiving/{id}/rejections")
    public Map<String, Object> reject(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
        receiving.reject(id, integer(b.get("lineNumber")), number(b.get("qty")), str(b.get("reason")));
        return receiving.get(id);
    }

    // ---- putaway
    @GetMapping("/sites/{siteId}/putaway/tasks")
    public List<Map<String, Object>> tasks(@PathVariable UUID siteId) { return putaway.openTasks(siteId); }

    /** {userId?, priority?} — any assignment type; userId null unassigns. Gated by role capability and trained-workflow allowance. */
    @PostMapping("/assignments/{id}/assign")
    public Map<String, Object> assign(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return dispatch.assign(id, uuid(b.get("userId")), integer(b.get("priority"))); }

    /** Operators eligible for a type of work at a site (capability + allowance). */
    @GetMapping("/sites/{siteId}/dispatch/eligible")
    public List<Map<String, Object>> eligible(@PathVariable UUID siteId, @RequestParam String type) { return dispatch.eligible(siteId, type.toUpperCase()); }

    /** {checkDigits} or {overrideLocation, overrideReason} */
    @PostMapping("/putaway/tasks/{taskId}/complete")
    public Map<String, Object> complete(@PathVariable UUID taskId, @RequestBody Map<String, Object> b) {
        return putaway.complete(taskId, str(b.get("checkDigits")), str(b.get("overrideLocation")), str(b.get("overrideReason")));
    }
}
