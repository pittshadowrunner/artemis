package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.AllocationService;
import com.artemis.wms.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.service.InventoryService.date;
import static com.artemis.wms.service.LocationService.str;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orders;
    private final AllocationService allocation;
    private final CapabilityService caps;

    public OrderController(OrderService orders, AllocationService allocation, CapabilityService caps) {
        this.orders = orders; this.allocation = allocation; this.caps = caps;
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.SELECTION_EXECUTE);
        UUID id = orders.create(UUID.fromString(body.get("siteId").toString()),
                str(body.get("orderNumber")), str(body.get("customerCode")),
                date(body.get("requestedShipDate")), str(body.get("dropLocation")),
                (List<Map<String, Object>>) body.get("lines"));
        return Map.of("orderId", id);
    }

    @PostMapping("/{id}/allocate")
    public Map<String, Object> allocate(@PathVariable UUID id) {
        caps.require(TenantContext.user(), null, Capabilities.WAVE_PLAN);
        return allocation.allocate(id);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) { return orders.get(id); }
}
