package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.ReceivingService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.service.InventoryService.date;

@RestController
@RequestMapping("/api/v1/receiving/manifests")
public class ReceivingController {

    private final ReceivingService receiving;
    private final CapabilityService caps;

    public ReceivingController(ReceivingService receiving, CapabilityService caps) {
        this.receiving = receiving; this.caps = caps;
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.RECEIVING_EXECUTE);
        UUID id = receiving.createManifest(UUID.fromString(body.get("siteId").toString()),
                (String) body.get("manifestNumber"), (String) body.get("carrier"),
                (String) body.get("trailerNumber"), date(body.get("expectedDate")),
                (List<Map<String, Object>>) body.get("lines"));
        return Map.of("manifestId", id);
    }

    @PostMapping("/{id}/arrive")
    public Map<String, Object> arrive(@PathVariable UUID id) {
        caps.require(TenantContext.user(), null, Capabilities.RECEIVING_EXECUTE);
        receiving.arrive(id);
        return Map.of("manifestId", id, "status", "ARRIVED");
    }

    @PostMapping("/{id}/receipts")
    public Map<String, Object> receive(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.RECEIVING_EXECUTE);
        return receiving.receive(id, body);
    }

    @PostMapping("/{id}/rejections")
    public Map<String, Object> reject(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.RECEIVING_EXECUTE);
        receiving.reject(id, Integer.parseInt(body.get("lineNumber").toString()),
                new BigDecimal(body.get("qty").toString()), (String) body.get("reason"));
        return Map.of("manifestId", id, "rejected", true);
    }

    @PostMapping("/{id}/close")
    public Map<String, Object> close(@PathVariable UUID id) {
        caps.require(TenantContext.user(), null, Capabilities.RECEIVING_EXECUTE);
        receiving.close(id);
        return Map.of("manifestId", id, "status", "CLOSED");
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) { return receiving.get(id); }
}
