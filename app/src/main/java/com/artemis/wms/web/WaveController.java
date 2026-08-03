package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.WaveService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.service.LocationService.str;

@RestController
@RequestMapping("/api/v1/waves")
public class WaveController {

    private final WaveService waves;
    private final CapabilityService caps;

    public WaveController(WaveService waves, CapabilityService caps) { this.waves = waves; this.caps = caps; }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.WAVE_PLAN);
        return waves.create(body);
    }

    @PostMapping("/{id}/release")
    public Map<String, Object> release(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.WAVE_PLAN);
        return waves.release(id, str(body.get("equipmentCode")), str(body.get("putMode")));
    }
}
