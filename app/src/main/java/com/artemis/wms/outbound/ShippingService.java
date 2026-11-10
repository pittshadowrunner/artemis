package com.artemis.wms.outbound;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.masterdata.LocationService;
import com.artemis.wms.platform.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.artemis.wms.platform.Values.*;

/**
 * Shipments group dropped orders for one customer; the packing list is
 * generated from what was actually picked — lots and expiry per pallet —
 * not from what was ordered.
 */
@Service
public class ShippingService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final OrderService orders;
    private final LocationService locations;
    public ShippingService(JdbcTemplate jdbc, AccessService access, OrderService orders, LocationService locations) { this.jdbc = jdbc; this.access = access; this.orders = orders; this.locations = locations; }

    /** {customer, orderNumbers:[...], carrier?, trailer?, dock?} */
    @Transactional
    public Map<String, Object> create(UUID siteId, Map<String, Object> spec) {
        UUID corp = access.requireTenant();
        access.require(siteId, Capabilities.SHIPPING_EXECUTE);
        String custCode = upper(spec.get("customer"));
        List<UUID> cust = jdbc.queryForList("SELECT customer_id FROM customer WHERE corporation_id = ? AND code = ?", UUID.class, corp, custCode);
        if (cust.isEmpty()) throw ApiException.badRequest("Unknown customer '" + custCode + "'.");
        List<String> numbers = strings(spec.get("orderNumbers"));
        if (numbers.isEmpty()) throw ApiException.badRequest("orderNumbers is required.");
        UUID dock = spec.get("dock") == null ? null : locations.idByCode(siteId, str(spec.get("dock")));
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shipment (shipment_id, corporation_id, site_id, shipment_number, customer_id, carrier, trailer_number, dock_location_id) VALUES (?, ?, ?, next_shipment_number(), ?, ?, ?, ?)",
            id, corp, siteId, cust.get(0), str(spec.get("carrier")), str(spec.get("trailer")), dock);
        for (String n : numbers) {
            UUID orderId = orders.idByNumber(siteId, n);
            Map<String, Object> o = jdbc.queryForMap("SELECT customer_id, status::text AS status FROM customer_order WHERE order_id = ?", orderId);
            if (!o.get("customer_id").equals(cust.get(0))) throw ApiException.badRequest("Order " + n + " belongs to another customer.");
            if (!"DROPPED".equals(o.get("status"))) throw ApiException.conflict("Order " + n + " is " + o.get("status") + ", not DROPPED.");
            jdbc.update("INSERT INTO shipment_order (shipment_id, order_id) VALUES (?, ?)", id, orderId);
        }
        generatePackingList(id);
        return get(id);
    }

    private void generatePackingList(UUID shipmentId) {
        UUID plId = UUID.randomUUID();
        String number = jdbc.queryForObject("SELECT shipment_number FROM shipment WHERE shipment_id = ?", String.class, shipmentId);
        jdbc.update("INSERT INTO packing_list (packing_list_id, shipment_id, document_number) VALUES (?, ?, ?)", plId, shipmentId, "PL-" + number);
        jdbc.update("""
            INSERT INTO packing_list_line (packing_list_id, line_number, order_id, item_id, qty, lot_number, expiration_date, lpn)
            SELECT ?, row_number() OVER (ORDER BY o.order_number, ol.line_number, inv.lpn), o.order_id, ol.item_id, a.picked_qty, inv.lot_number, inv.expiration_date, inv.lpn
            FROM shipment_order so JOIN customer_order o ON o.order_id = so.order_id
            JOIN customer_order_line ol ON ol.order_id = o.order_id
            JOIN allocation a ON a.order_line_id = ol.order_line_id AND a.picked_qty > 0
            JOIN inventory inv ON inv.inventory_id = a.inventory_id
            WHERE so.shipment_id = ?""", plId, shipmentId);
    }

    @Transactional
    public Map<String, Object> ship(UUID shipmentId) {
        Map<String, Object> s = jdbc.queryForMap("SELECT site_id, status::text AS status FROM shipment WHERE shipment_id = ?", shipmentId);
        access.require((UUID) s.get("site_id"), Capabilities.SHIPPING_EXECUTE);
        if ("SHIPPED".equals(s.get("status"))) throw ApiException.conflict("Already shipped.");
        jdbc.update("UPDATE shipment SET status = 'SHIPPED', shipped_at = now() WHERE shipment_id = ?", shipmentId);
        jdbc.update("UPDATE customer_order SET status = 'SHIPPED', updated_at = now() WHERE order_id IN (SELECT order_id FROM shipment_order WHERE shipment_id = ?)", shipmentId);
        // The picked units leave the building with the truck.
        String units = "v.picked_from IS NOT NULL AND v.status = 'PICKED' AND v.zone_order_id IN (SELECT z.zone_order_id FROM zone_order z JOIN shipment_order so ON so.order_id = z.order_id WHERE so.shipment_id = ?)";
        jdbc.update("INSERT INTO inventory_movement (inventory_id, from_location, qty, movement_type, performed_by) SELECT v.inventory_id, v.location_id, v.qty, 'SHIP', ? FROM inventory v WHERE " + units, com.artemis.wms.tenancy.TenantContext.user(), shipmentId);
        jdbc.update("UPDATE inventory v SET status = 'SHIPPED', location_id = NULL, equipment_id = NULL, cart_position = NULL, updated_at = now() WHERE " + units, shipmentId);
        return get(shipmentId);
    }

    public List<Map<String, Object>> list(UUID siteId) { return jdbc.queryForList("SELECT * FROM v_shipping_progress WHERE site_id = ? ORDER BY created_at DESC", siteId); }

    public Map<String, Object> get(UUID shipmentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM v_shipping_progress WHERE shipment_id = ?", shipmentId);
        if (rows.isEmpty()) throw ApiException.notFound("Shipment not found.");
        Map<String, Object> s = new LinkedHashMap<>(rows.get(0));
        s.put("packingList", jdbc.queryForList("""
            SELECT pl.document_number, pll.line_number, o.order_number, i.sku::text AS sku, i.description, pll.qty, pll.lot_number, pll.expiration_date, pll.lpn
            FROM packing_list pl JOIN packing_list_line pll ON pll.packing_list_id = pl.packing_list_id
            JOIN customer_order o ON o.order_id = pll.order_id JOIN item i ON i.item_id = pll.item_id
            WHERE pl.shipment_id = ? ORDER BY pll.line_number""", shipmentId));
        return s;
    }
}
