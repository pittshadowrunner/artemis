package com.artemis.wms.outbound;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.masterdata.ItemService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.platform.BulkResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

import static com.artemis.wms.platform.Values.*;

/**
 * Customer orders. Zone orders derive from the lines by trigger, so an
 * order spanning dry + frozen is two units of work the moment it exists.
 * Route/stop are snapshotted from the customer so a route change later
 * doesn't rewrite history.
 */
@Service
public class OrderService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final ItemService items;
    private final TransactionTemplate tx;
    public OrderService(JdbcTemplate jdbc, AccessService access, ItemService items, TransactionTemplate tx) { this.jdbc = jdbc; this.access = access; this.items = items; this.tx = tx; }

    /** Bulk create; existing order numbers are left untouched (orders are not upserted). */
    public BulkResult bulk(UUID siteId, List<Map<String, Object>> orders) {
        UUID corp = access.requireTenant();
        access.require(siteId, Capabilities.WAVE_PLAN);
        BulkResult r = new BulkResult();
        int row = 0;
        for (Map<String, Object> o : orders) {
            row++;
            String number = upper(o.get("orderNumber"));
            try {
                if (number == null) throw ApiException.badRequest("orderNumber is required.");
                Integer exists = jdbc.queryForObject("SELECT count(*) FROM customer_order WHERE site_id = ? AND order_number = ?", Integer.class, siteId, number);
                if (exists != null && exists > 0) { r.skipped(); continue; }
                tx.executeWithoutResult(s -> create(corp, siteId, number, o));
                r.created();
            } catch (ApiException e) { r.error(row, number, e.getMessage()); }
              catch (Exception e)    { r.error(row, number, rootMessage(e)); }
        }
        return r;
    }

    private void create(UUID corp, UUID siteId, String number, Map<String, Object> o) {
        String custCode = upper(o.get("customer"));
        List<Map<String, Object>> cust = jdbc.queryForList("SELECT customer_id, route_code, stop_sequence FROM customer WHERE corporation_id = ? AND code = ?", corp, custCode);
        if (cust.isEmpty()) throw ApiException.badRequest("Unknown customer '" + custCode + "'.");
        List<Map<String, Object>> lines = maps(o.get("lines"));
        if (lines.isEmpty()) throw ApiException.badRequest("An order needs at least one line.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO customer_order (order_id, corporation_id, site_id, customer_id, order_number, rush, priority, requested_ship_date, route_code, stop_sequence, notes)
            VALUES (?, ?, ?, ?, ?, COALESCE(?, false), COALESCE(?, CASE WHEN ? THEN 8 ELSE 5 END), ?, ?, ?, ?)""",
            id, corp, siteId, cust.get(0).get("customer_id"), number, bool(o.get("rush")), integer(o.get("priority")), is(o.get("rush")),
            date(o.get("requestedShipDate")), cust.get(0).get("route_code"), cust.get(0).get("stop_sequence"), str(o.get("notes")));
        int n = 0;
        for (Map<String, Object> line : lines) {
            n++;
            BigDecimal qty = number(line.get("qty"));
            if (qty == null || qty.signum() <= 0) throw ApiException.badRequest("Line " + n + ": qty must be positive.");
            jdbc.update("INSERT INTO customer_order_line (order_id, line_number, item_id, ordered_qty) VALUES (?, ?, ?, ?)", id, n, items.idBySku(corp, str(line.get("sku"))), qty);
        }
    }

    public List<Map<String, Object>> list(UUID siteId, String status) {
        return jdbc.queryForList("SELECT * FROM v_order_progress WHERE site_id = ? AND (?::text IS NULL OR status::text = ?) ORDER BY rush DESC, priority DESC, requested_ship_date NULLS LAST, order_number", siteId, status, status);
    }

    public Map<String, Object> get(UUID orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM v_order_progress WHERE order_id = ?", orderId);
        if (rows.isEmpty()) throw ApiException.notFound("Order not found.");
        Map<String, Object> o = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> lines = jdbc.queryForList("""
            SELECT ol.order_line_id, ol.line_number, i.sku::text AS sku, i.description, i.temp_zone::text AS temp_zone, ol.ordered_qty, ol.allocated_qty, ol.picked_qty
            FROM customer_order_line ol JOIN item i ON i.item_id = ol.item_id WHERE ol.order_id = ? ORDER BY ol.line_number""", orderId);
        for (Map<String, Object> line : lines)
            line.put("allocations", jdbc.queryForList("""
                SELECT inv.lpn, l.code AS location, a.qty, a.picked_qty AS picked, inv.lot_number AS lot, inv.expiration_date AS expires, a.bypass
                FROM allocation a JOIN inventory inv ON inv.inventory_id = a.inventory_id LEFT JOIN location l ON l.location_id = inv.location_id
                WHERE a.order_line_id = ? ORDER BY a.created_at""", line.get("order_line_id")));
        o.put("lines", lines);
        o.put("zoneOrders", jdbc.queryForList("""
            SELECT z.zone_order_id, z.temp_zone::text AS temp_zone, w.wave_number, w.status::text AS wave_status, a.assignment_number, a.status::text AS assignment_status,
                   a.priority AS assignment_priority, u.display_name AS operator, a.assigned_to AS operator_id, e.code AS equipment, ac.cart_position,
                   z.dropped_at, l.code AS drop_location,
                   (SELECT count(*) FROM customer_order_line ol JOIN item i ON i.item_id = ol.item_id WHERE ol.order_id = z.order_id AND i.temp_zone = z.temp_zone) AS lines
            FROM zone_order z LEFT JOIN wave w ON w.wave_id = z.wave_id LEFT JOIN assignment a ON a.assignment_id = z.assignment_id LEFT JOIN location l ON l.location_id = z.drop_location_id
            LEFT JOIN app_user u ON u.user_id = a.assigned_to LEFT JOIN equipment e ON e.equipment_id = a.equipment_id
            LEFT JOIN assignment_container ac ON ac.zone_order_id = z.zone_order_id AND ac.assignment_id = a.assignment_id
            WHERE z.order_id = ? ORDER BY z.temp_zone""", orderId));
        o.put("customer", jdbc.queryForList("""
            SELECT c.code, c.name, c.address_line1, c.address_line2, c.city, c.state_province, c.postal_code, c.country, c.contact_phone, c.contact_email::text AS contact_email,
                   c.delivery_window_start, c.delivery_window_end, c.min_shelf_life_days, c.preferred_carrier
            FROM customer_order co JOIN customer c ON c.customer_id = co.customer_id WHERE co.order_id = ?""", orderId).stream().findFirst().orElse(null));
        o.put("shipment", jdbc.queryForList("""
            SELECT s.shipment_number, s.status::text AS status, s.carrier, s.shipped_at
            FROM shipment_order so JOIN shipment s ON s.shipment_id = so.shipment_id WHERE so.order_id = ?""", orderId).stream().findFirst().orElse(null));
        return o;
    }

    public UUID idByNumber(UUID siteId, String number) {
        List<UUID> ids = jdbc.queryForList("SELECT order_id FROM customer_order WHERE site_id = ? AND order_number = ?", UUID.class, siteId, number.toUpperCase());
        if (ids.isEmpty()) throw ApiException.notFound("Order " + number + " not found.");
        return ids.get(0);
    }
}
