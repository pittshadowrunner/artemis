package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ShippingService {

    private final JdbcTemplate jdbc;

    public ShippingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** From DROPPED orders, one customer. */
    @Transactional
    public Map<String, Object> create(UUID siteId, List<UUID> orderIds, String carrier, String trailer) {
        if (orderIds == null || orderIds.isEmpty()) throw ApiException.badRequest("At least one order required.");
        List<Map<String, Object>> orders = jdbc.queryForList("""
            SELECT order_id, customer_id, status::text AS status FROM customer_order
            WHERE order_id = ANY(?)
            """, (Object) orderIds.toArray(UUID[]::new));
        if (orders.size() != orderIds.size()) throw ApiException.notFound("One or more orders not found.");
        Set<UUID> customers = new HashSet<>();
        for (Map<String, Object> o : orders) {
            if (!"DROPPED".equals(o.get("status")))
                throw ApiException.conflict("All orders must be DROPPED (order " + o.get("order_id") + " is " + o.get("status") + ").");
            customers.add((UUID) o.get("customer_id"));
        }
        if (customers.size() > 1) throw ApiException.badRequest("A shipment covers one customer.");

        UUID shipmentId = UUID.randomUUID();
        Long n = jdbc.queryForObject("SELECT count(*) + 1 FROM shipment WHERE site_id = ?", Long.class, siteId);
        String number = "SHP-" + java.time.LocalDate.now() + "-" + String.format("%03d", n);
        jdbc.update("""
            INSERT INTO shipment (shipment_id, corporation_id, site_id, shipment_number, customer_id, carrier, trailer_number)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, shipmentId, TenantContext.corp(), siteId, number, customers.iterator().next(), carrier, trailer);
        for (UUID orderId : orderIds)
            jdbc.update("INSERT INTO shipment_order (shipment_id, order_id) VALUES (?, ?)", shipmentId, orderId);
        return Map.of("shipmentId", shipmentId, "shipmentNumber", number);
    }

    /**
     * The packing list is generated from picked reality — lots, expiration
     * dates, captured catch weights, serials — because the document on the
     * truck must describe the physical freight, not what was ordered.
     */
    @Transactional
    public Map<String, Object> packingList(UUID shipmentId) {
        UUID plId = UUID.randomUUID();
        Long docNo = jdbc.queryForObject("SELECT nextval('packing_list_seq')", Long.class);
        jdbc.update("""
            INSERT INTO packing_list (packing_list_id, shipment_id, document_number)
            VALUES (?, ?, ?)
            """, plId, shipmentId, "PL-" + docNo);

        List<Map<String, Object>> picked = jdbc.queryForList("""
            SELECT so.order_id, inv.item_id, inv.qty, inv.lot_number, inv.expiration_date,
                   inv.actual_weight_kg, inv.inventory_id
            FROM shipment_order so
            JOIN customer_order_line col ON col.order_id = so.order_id
            JOIN allocation al ON al.order_line_id = col.order_line_id
            JOIN inventory base ON base.inventory_id = al.inventory_id
            JOIN inventory inv ON inv.status = 'PICKED'
                 AND (inv.inventory_id = base.inventory_id OR inv.lpn LIKE base.lpn || '-P%')
                 AND inv.item_id = col.item_id
            WHERE so.shipment_id = ?
            ORDER BY so.order_id, inv.item_id
            """, shipmentId);

        int line = 0;
        Set<UUID> seen = new HashSet<>();
        for (Map<String, Object> p : picked) {
            UUID invId = (UUID) p.get("inventory_id");
            if (!seen.add(invId)) continue;
            line++;
            List<String> serials = jdbc.queryForList(
                "SELECT serial_number FROM inventory_serial WHERE inventory_id = ?", String.class, invId);
            jdbc.update("""
                INSERT INTO packing_list_line (packing_list_id, line_number, order_id, item_id, qty,
                    lot_number, serials, actual_weight_kg, expiration_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, plId, line, p.get("order_id"), p.get("item_id"), p.get("qty"), p.get("lot_number"),
                serials.isEmpty() ? null : serials.toArray(String[]::new),
                p.get("actual_weight_kg"), p.get("expiration_date"));
        }
        if (line == 0) throw ApiException.conflict("No picked inventory found for this shipment.");
        return Map.of("packingListId", plId, "documentNumber", "PL-" + docNo, "lines", line);
    }

    /** Packing list required first. Inventory -> SHIPPED, SHIP movements, orders closed. */
    @Transactional
    public Map<String, Object> ship(UUID shipmentId) {
        Integer pl = jdbc.queryForObject(
            "SELECT count(*) FROM packing_list WHERE shipment_id = ?", Integer.class, shipmentId);
        if (pl == null || pl == 0)
            throw ApiException.conflict("Generate the packing list first — the truck doesn't leave undocumented.");

        List<Map<String, Object>> inventory = jdbc.queryForList("""
            SELECT DISTINCT inv.inventory_id, inv.location_id, inv.qty
            FROM shipment_order so
            JOIN customer_order_line col ON col.order_id = so.order_id
            JOIN allocation al ON al.order_line_id = col.order_line_id
            JOIN inventory base ON base.inventory_id = al.inventory_id
            JOIN inventory inv ON inv.status = 'PICKED'
                 AND (inv.inventory_id = base.inventory_id OR inv.lpn LIKE base.lpn || '-P%')
            WHERE so.shipment_id = ?
            """, shipmentId);
        for (Map<String, Object> inv : inventory) {
            jdbc.update("""
                INSERT INTO inventory_movement (inventory_id, from_location, qty, movement_type, performed_by)
                VALUES (?, ?, ?, 'SHIP', ?)
                """, inv.get("inventory_id"), inv.get("location_id"), inv.get("qty"), TenantContext.user());
            jdbc.update("UPDATE inventory SET status = 'SHIPPED', updated_at = now() WHERE inventory_id = ?",
                inv.get("inventory_id"));
        }
        jdbc.update("UPDATE shipment SET status = 'SHIPPED', shipped_at = now() WHERE shipment_id = ?", shipmentId);
        jdbc.update("""
            UPDATE customer_order SET status = 'SHIPPED'
            WHERE order_id IN (SELECT order_id FROM shipment_order WHERE shipment_id = ?)
            """, shipmentId);
        return Map.of("shipmentId", shipmentId, "shippedUnits", inventory.size());
    }
}
