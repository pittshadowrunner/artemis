package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.EquipmentService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Site assets are infrastructure, same as locations — LOCATION_MANAGE gates writes. */
@RestController
@RequestMapping("/api/v1/equipment")
public class EquipmentController {

    private final EquipmentService equipment;
    private final CapabilityService caps;

    public EquipmentController(EquipmentService equipment, CapabilityService caps) {
        this.equipment = equipment; this.caps = caps;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.LOCATION_MANAGE);
        return equipment.create(body);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam UUID siteId) {
        return equipment.list(siteId);
    }
}
