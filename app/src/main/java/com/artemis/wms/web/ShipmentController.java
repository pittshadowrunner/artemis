package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.ShippingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.service.LocationService.str;

@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentController {

    private final ShippingService shipping;
    private final CapabilityService caps;

    public ShipmentController(ShippingService shipping, CapabilityService caps) {
        this.shipping = shipping; this.caps = caps;
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.SHIPPING_EXECUTE);
        List<UUID> orderIds = ((List<Object>) body.get("orderIds")).stream()
                .map(o -> UUID.fromString(o.toString())).toList();
        return shipping.create(UUID.fromString(body.get("siteId").toString()), orderIds,
                str(body.get("carrier")), str(body.get("trailerNumber")));
    }

    @PostMapping("/{id}/packing-list")
    public Map<String, Object> packingList(@PathVariable UUID id) {
        caps.require(TenantContext.user(), null, Capabilities.SHIPPING_EXECUTE);
        return shipping.packingList(id);
    }

    @PostMapping("/{id}/ship")
    public Map<String, Object> ship(@PathVariable UUID id) {
        caps.require(TenantContext.user(), null, Capabilities.SHIPPING_EXECUTE);
        return shipping.ship(id);
    }
}
