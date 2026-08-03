package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.service.LocationService.*;
import static com.artemis.wms.service.InventoryService.date;

@Service
public class OrderService {

    private final JdbcTemplate jdbc;

    public OrderService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public UUID create(UUID siteId, String orderNumber, String customerCode,
                       LocalDate requestedShipDate, String dropLocationCode, List<Map<String, Object>> lines) {
        List<UUID> customers = jdbc.queryForList(
            "SELECT customer_id FROM customer WHERE corporation_id = ? AND code = ?",
            UUID.class, TenantContext.corp(), customerCode);
        if (customers.isEmpty()) throw ApiException.badRequest("Unknown customer '" + customerCode + "'.");

        UUID dropLocation = null;
        if (dropLocationCode != null) {
            List<UUID> drops = jdbc.queryForList(
                "SELECT location_id FROM location WHERE site_id = ? AND code = ? AND loc_type = 'DROP'",
                UUID.class, siteId, dropLocationCode);
            if (drops.isEmpty()) throw ApiException.badRequest("Unknown drop location '" + dropLocationCode + "'.");
            dropLocation = drops.get(0);
        }

        UUID orderId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO customer_order (order_id, corporation_id, site_id, customer_id, order_number,
                requested_ship_date, drop_location_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, orderId, TenantContext.corp(), siteId, customers.get(0), orderNumber,
            requestedShipDate, dropLocation);
        int lineNo = 0;
        for (Map<String, Object> line : lines) {
            lineNo++;
            String sku = str(line.get("sku"));
            List<UUID> items = jdbc.queryForList(
                "SELECT item_id FROM item WHERE corporation_id = ? AND sku = ?::citext",
                UUID.class, TenantContext.corp(), sku);
            if (items.isEmpty()) throw ApiException.badRequest("Unknown SKU '" + sku + "' on line " + lineNo + ".");
            jdbc.update("""
                INSERT INTO customer_order_line (order_id, line_number, item_id, ordered_qty)
                VALUES (?, ?, ?, ?)
                """, orderId, lineNo, items.get(0), num(line.get("qty"), "qty"));
        }
        return orderId;
    }

    public Map<String, Object> get(UUID orderId) {
        Map<String, Object> o = jdbc.queryForMap("""
            SELECT o.order_id, o.order_number, o.status::text AS status, o.requested_ship_date,
                   c.code AS customer_code, c.name AS customer_name
            FROM customer_order o JOIN customer c ON c.customer_id = o.customer_id
            WHERE o.order_id = ?
            """, orderId);
        o.put("lines", jdbc.queryForList("""
            SELECT l.line_number, i.sku::text AS sku, l.ordered_qty, l.allocated_qty, l.picked_qty
            FROM customer_order_line l JOIN item i ON i.item_id = l.item_id
            WHERE l.order_id = ? ORDER BY l.line_number
            """, orderId));
        return o;
    }
}
