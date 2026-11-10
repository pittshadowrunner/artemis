package com.artemis.wms.outbound;

import com.artemis.wms.platform.BulkResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.platform.Values.*;

@RestController
@RequestMapping("/api/v1")
public class OutboundController {
    private final OrderService orders;
    private final WaveService waves;
    private final SelectionService selection;
    private final ShippingService shipping;
    public OutboundController(OrderService orders, WaveService waves, SelectionService selection, ShippingService shipping) { this.orders = orders; this.waves = waves; this.selection = selection; this.shipping = shipping; }

    // ---- orders
    @GetMapping("/sites/{siteId}/orders") public List<Map<String, Object>> orders(@PathVariable UUID siteId, @RequestParam(required = false) String status) { return orders.list(siteId, upper(status)); }
    /** [{orderNumber, customer, rush?, priority?, requestedShipDate?, lines:[{sku, qty}]}] */
    @PostMapping("/sites/{siteId}/orders/bulk") public BulkResult ordersBulk(@PathVariable UUID siteId, @RequestBody List<Map<String, Object>> body) { return orders.bulk(siteId, body); }
    @GetMapping("/orders/{id}") public Map<String, Object> order(@PathVariable UUID id) { return orders.get(id); }
    @GetMapping("/sites/{siteId}/orders/{number}") public Map<String, Object> orderByNumber(@PathVariable UUID siteId, @PathVariable String number) { return orders.get(orders.idByNumber(siteId, number)); }

    // ---- waves
    @GetMapping("/sites/{siteId}/waves") public List<Map<String, Object>> waves(@PathVariable UUID siteId) { return waves.list(siteId); }
    /** {type, tempZone, batchSize?, maxOrders?, orderNumbers?, routeCode?, customer?, cutoff?, area?} */
    @PostMapping("/sites/{siteId}/waves") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> plan(@PathVariable UUID siteId, @RequestBody Map<String, Object> spec) { return waves.plan(siteId, spec); }
    @GetMapping("/waves/{id}") public Map<String, Object> wave(@PathVariable UUID id) { return waves.get(id); }
    /** {equipment?, build?} — cart code (its positions set the batch size); build: "MANUAL" leaves the zone orders for the floor to build */
    @PostMapping("/waves/{id}/release") public Map<String, Object> release(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) {
        return waves.release(id, b == null ? null : str(b.get("equipment")), b != null && "MANUAL".equalsIgnoreCase(str(b.get("build"))));
    }

    // ---- selection
    @GetMapping("/sites/{siteId}/assignments") public List<Map<String, Object>> assignments(@PathVariable UUID siteId, @RequestParam(required = false) String type) { return selection.open(siteId, upper(type)); }
    @GetMapping("/assignments/{id}") public Map<String, Object> assignment(@PathVariable UUID id) { return selection.get(id); }
    /** {position, putCheckDigits, tote?, shippingLpn?} */
    @PostMapping("/assignments/{id}/induct") public Map<String, Object> induct(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
        selection.induct(id, integer(b.get("position")), str(b.get("putCheckDigits")), str(b.get("tote")), str(b.get("shippingLpn")));
        return Map.of("assignmentId", id, "position", b.get("position"), "inducted", true);
    }
    /** {checkDigits, putCheckDigits, qty?} */
    @PostMapping("/selection/tasks/{taskId}/pick") public Map<String, Object> pick(@PathVariable UUID taskId, @RequestBody Map<String, Object> b) {
        return selection.pick(taskId, str(b.get("checkDigits")), str(b.get("putCheckDigits")), number(b.get("qty")));
    }
    /** {dropLocation} */
    @PostMapping("/assignments/{id}/drop") public Map<String, Object> drop(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return selection.drop(id, str(b.get("dropLocation"))); }

    // ---- shipping
    @GetMapping("/sites/{siteId}/shipments") public List<Map<String, Object>> shipments(@PathVariable UUID siteId) { return shipping.list(siteId); }
    /** {customer, orderNumbers:[...], carrier?, trailer?, dock?} */
    @PostMapping("/sites/{siteId}/shipments") @ResponseStatus(HttpStatus.CREATED) public Map<String, Object> shipment(@PathVariable UUID siteId, @RequestBody Map<String, Object> spec) { return shipping.create(siteId, spec); }
    @GetMapping("/shipments/{id}") public Map<String, Object> shipmentGet(@PathVariable UUID id) { return shipping.get(id); }
    @PostMapping("/shipments/{id}/ship") public Map<String, Object> ship(@PathVariable UUID id) { return shipping.ship(id); }
}
