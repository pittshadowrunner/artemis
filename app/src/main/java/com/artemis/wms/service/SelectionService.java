package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static com.artemis.wms.service.LocationService.str;

/**
 * Fully system-driven selection, both sides verified: slot check digits on
 * the pick, cart-position digits / tote barcode / inducted LPN on the put.
 * Partial picks split the LPN. Shorts require a reason. Order status
 * advances RELEASED -> PICKING on first pick -> PICKED when complete.
 */
@Service
public class SelectionService {

    private final JdbcTemplate jdbc;

    public SelectionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> tasks(UUID assignmentId) {
        return jdbc.queryForList("""
            SELECT t.task_id, t.seq, t.qty, t.status::text AS status, t.check_digits, t.put_check_digits,
                   t.cart_position, t.spoken_prompt, i.lpn, it.sku::text AS sku, it.description, l.code AS from_code,
                   ac.order_id, ac.inducted_lpn
            FROM assignment_task t
            LEFT JOIN inventory i ON i.inventory_id = t.inventory_id
            LEFT JOIN item it ON it.item_id = t.item_id
            LEFT JOIN location l ON l.location_id = t.from_location
            LEFT JOIN assignment_container ac ON ac.assignment_id = t.assignment_id
                 AND ac.cart_position = t.cart_position
            WHERE t.assignment_id = ?
            ORDER BY t.seq
            """, assignmentId);
    }

    /** First pick of an order in SHIPPING_CONTAINER mode: stamp the LPN, reuse it for every later pick. */
    @Transactional
    public void induct(UUID assignmentId, UUID orderId, String lpn) {
        int n = jdbc.update("""
            UPDATE assignment_container SET inducted_lpn = ?, inducted_at = now()
            WHERE assignment_id = ? AND order_id = ? AND inducted_lpn IS NULL
            """, lpn, assignmentId, orderId);
        if (n == 0) throw ApiException.conflict("Order not on this assignment, or an LPN is already inducted.");
    }

    @Transactional
    public Map<String, Object> pick(UUID taskId, Map<String, Object> body) {
        Map<String, Object> t = jdbc.queryForMap("""
            SELECT t.assignment_id, t.inventory_id, t.item_id, t.from_location, t.qty, t.check_digits,
                   t.put_check_digits, t.cart_position, t.status::text AS status,
                   a.site_id, a.equipment_id
            FROM assignment_task t JOIN assignment a ON a.assignment_id = t.assignment_id
            WHERE t.task_id = ?
            """, taskId);
        if (!"OPEN".equals(t.get("status"))) throw ApiException.conflict("Task is not open.");
        UUID assignmentId = (UUID) t.get("assignment_id");

        // ---- pick side: slot check digits must match
        String spoken = str(body.get("checkDigits"));
        if (spoken == null || !spoken.equals(t.get("check_digits")))
            throw ApiException.conflict("Slot check digit mismatch — verify the location.");

        // which order does this task feed?
        Map<String, Object> container = jdbc.queryForMap("""
            SELECT ac.order_id, ac.inducted_lpn, ac.container_id, c.barcode, c.check_digits AS tote_digits
            FROM assignment_container ac
            LEFT JOIN container c ON c.container_id = ac.container_id
            WHERE ac.assignment_id = ? AND ac.cart_position = ?
            """, assignmentId, t.get("cart_position"));
        UUID orderId = (UUID) container.get("order_id");

        // ---- put side: cart position digits, tote barcode, or inducted shipping-container LPN
        String putConfirm = str(body.get("putConfirmation"));
        String putDigits = (String) t.get("put_check_digits");
        String inducted = (String) container.get("inducted_lpn");
        boolean shippingMode = putDigits == null;
        if (shippingMode) {
            if (inducted == null)
                throw ApiException.conflict("Nothing inducted for this order yet — induct a shipping-container LPN on the first pick.");
            if (putConfirm == null || !putConfirm.equals(inducted))
                throw ApiException.conflict("Put confirmation must match the order's inducted LPN " + inducted + ".");
        } else {
            boolean ok = putConfirm != null && (putConfirm.equals(putDigits)
                    || putConfirm.equals(container.get("barcode"))
                    || putConfirm.equals(container.get("tote_digits")));
            if (!ok) throw ApiException.conflict("Put confirmation mismatch — say the position digits or scan the tote.");
        }

        // ---- quantity: full, partial (split the LPN), or short with a reason
        BigDecimal taskQty = (BigDecimal) t.get("qty");
        BigDecimal pickedQty = body.get("qty") == null ? taskQty : new BigDecimal(body.get("qty").toString());
        if (pickedQty.compareTo(taskQty) > 0) throw ApiException.badRequest("Cannot pick more than the task quantity.");
        String shortReason = str(body.get("shortReason"));
        boolean isShort = pickedQty.compareTo(taskQty) < 0;
        if (isShort && shortReason == null)
            throw ApiException.badRequest("Short pick requires a reason — shorts are logged, never silently rounded.");

        UUID inventoryId = (UUID) t.get("inventory_id");
        Map<String, Object> inv = jdbc.queryForMap("""
            SELECT lpn, qty, lot_number, expiration_date, arrival_date, actual_weight_kg, location_id
            FROM inventory WHERE inventory_id = ?
            """, inventoryId);
        BigDecimal invQty = (BigDecimal) inv.get("qty");

        UUID pickedRecord;
        if (pickedQty.compareTo(invQty) < 0) {
            // split: picked quantity moves to a child record, remainder stays AVAILABLE in the slot
            pickedRecord = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO inventory (inventory_id, corporation_id, site_id, lpn, item_id, location_id,
                    qty, status, lot_number, expiration_date, arrival_date, actual_weight_kg)
                SELECT ?, corporation_id, site_id, lpn || '-P' || substr(?::text, 1, 4), item_id, location_id,
                    ?, 'PICKED', lot_number, expiration_date, arrival_date, actual_weight_kg
                FROM inventory WHERE inventory_id = ?
                """, pickedRecord, pickedRecord, pickedQty, inventoryId);
            jdbc.update("""
                UPDATE inventory SET qty = qty - ?, status = 'AVAILABLE', updated_at = now()
                WHERE inventory_id = ?
                """, pickedQty, inventoryId);
        } else {
            pickedRecord = inventoryId;
            jdbc.update("UPDATE inventory SET status = 'PICKED', updated_at = now() WHERE inventory_id = ?", inventoryId);
        }
        jdbc.update("""
            INSERT INTO inventory_movement (inventory_id, from_location, qty, movement_type, performed_by, assignment_id)
            VALUES (?, ?, ?, 'PICK', ?, ?)
            """, pickedRecord, t.get("from_location"), pickedQty, TenantContext.user(), assignmentId);
        jdbc.update("""
            UPDATE assignment_task SET status = 'COMPLETE', completed_at = now() WHERE task_id = ?
            """, taskId);
        jdbc.update("""
            UPDATE customer_order_line SET picked_qty = picked_qty + ?
            WHERE order_line_id = (SELECT order_line_id FROM allocation WHERE inventory_id = ? LIMIT 1)
            """, pickedQty, inventoryId);
        jdbc.update("UPDATE customer_order SET status = 'PICKING' WHERE order_id = ? AND status = 'RELEASED'", orderId);
        jdbc.update("""
            UPDATE customer_order SET status = 'PICKED'
            WHERE order_id = ? AND status = 'PICKING' AND NOT EXISTS
                (SELECT 1 FROM customer_order_line WHERE order_id = ? AND picked_qty < allocated_qty)
            """, orderId, orderId);
        jdbc.update("""
            UPDATE assignment SET status = 'COMPLETE', completed_at = now()
            WHERE assignment_id = ? AND NOT EXISTS
                (SELECT 1 FROM assignment_task WHERE assignment_id = ? AND status <> 'COMPLETE')
            """, assignmentId, assignmentId);
        jdbc.update("""
            UPDATE wave SET status = 'COMPLETE', completed_at = now()
            WHERE wave_id = (SELECT wave_id FROM assignment WHERE assignment_id = ?)
              AND NOT EXISTS (
                SELECT 1 FROM assignment a JOIN assignment_task at2 ON at2.assignment_id = a.assignment_id
                WHERE a.wave_id = (SELECT wave_id FROM assignment WHERE assignment_id = ?)
                  AND at2.status <> 'COMPLETE')
            """, assignmentId, assignmentId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("pickedQty", pickedQty);
        out.put("short", isShort);
        if (isShort) out.put("shortReason", shortReason);
        return out;
    }

    /** Drop all of an order's picked inventory to a drop location; order -> DROPPED. */
    @Transactional
    public Map<String, Object> drop(UUID orderId, String dropLocationCode) {
        Map<String, Object> order = jdbc.queryForMap("""
            SELECT site_id, status::text AS status, drop_location_id FROM customer_order WHERE order_id = ?
            """, orderId);
        if (!"PICKED".equals(order.get("status")))
            throw ApiException.conflict("Order must be PICKED to drop (is " + order.get("status") + ").");
        UUID siteId = (UUID) order.get("site_id");

        UUID dropLocation = (UUID) order.get("drop_location_id");   // honor a preset drop on the order
        if (dropLocation == null) {
            if (dropLocationCode == null) throw ApiException.badRequest("No preset drop on the order — supply dropLocation.");
            dropLocation = jdbc.queryForObject(
                "SELECT location_id FROM location WHERE site_id = ? AND code = ? AND loc_type = 'DROP'",
                UUID.class, siteId, dropLocationCode);
        }
        List<UUID> picked = jdbc.queryForList("""
            SELECT DISTINCT inv.inventory_id
            FROM customer_order_line col
            JOIN allocation al ON al.order_line_id = col.order_line_id
            JOIN inventory base ON base.inventory_id = al.inventory_id
            JOIN inventory inv ON inv.status = 'PICKED'
                 AND (inv.inventory_id = base.inventory_id OR inv.lpn LIKE base.lpn || '-P%')
            WHERE col.order_id = ?
            """, UUID.class, orderId);
        for (UUID invId : picked) {
            jdbc.update("""
                INSERT INTO inventory_movement (inventory_id, from_location, to_location, qty, movement_type, performed_by)
                SELECT inventory_id, location_id, ?, qty, 'DROP', ? FROM inventory WHERE inventory_id = ?
                """, dropLocation, TenantContext.user(), invId);
            jdbc.update("UPDATE inventory SET location_id = ?, updated_at = now() WHERE inventory_id = ?",
                dropLocation, invId);
        }
        jdbc.update("UPDATE customer_order SET status = 'DROPPED' WHERE order_id = ?", orderId);
        return Map.of("orderId", orderId, "dropped", picked.size());
    }
}
