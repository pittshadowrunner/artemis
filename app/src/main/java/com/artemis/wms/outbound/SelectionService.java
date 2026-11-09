package com.artemis.wms.outbound;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.masterdata.LocationService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static com.artemis.wms.platform.Values.*;

/**
 * Selection execution: the operator's view of an assignment, check-digit
 * verified picks (slot side and put side), short picks, and the drop.
 * Picking decrements the pallet in place; a pallet picked to zero is
 * PICKED, never deleted, so the journal stays whole.
 */
@Service
public class SelectionService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final LocationService locations;
    public SelectionService(JdbcTemplate jdbc, AccessService access, LocationService locations) { this.jdbc = jdbc; this.access = access; this.locations = locations; }

    public List<Map<String, Object>> open(UUID siteId, String type) {
        return jdbc.queryForList("""
            SELECT a.assignment_id, a.assignment_number, a.assignment_type::text AS type, a.status::text AS status, a.priority, a.temp_zone::text AS temp_zone,
                   w.wave_number, e.code AS equipment, u.display_name AS assigned_to,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id) AS tasks,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id AND t.status = 'COMPLETE') AS done,
                   (SELECT count(*) FROM assignment_container ac WHERE ac.assignment_id = a.assignment_id) AS orders, a.created_at
            FROM assignment a LEFT JOIN wave w ON w.wave_id = a.wave_id LEFT JOIN equipment e ON e.equipment_id = a.equipment_id LEFT JOIN app_user u ON u.user_id = a.assigned_to
            WHERE a.site_id = ? AND a.status IN ('OPEN','ASSIGNED','IN_PROGRESS') AND (?::text IS NULL OR a.assignment_type::text = ?)
            ORDER BY a.priority DESC, a.created_at""", siteId, type, type);
    }

    public Map<String, Object> get(UUID assignmentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.*, a.assignment_type::text AS assignment_type, a.status::text AS status, a.temp_zone::text AS temp_zone, w.wave_number, e.code AS equipment FROM assignment a LEFT JOIN wave w ON w.wave_id = a.wave_id LEFT JOIN equipment e ON e.equipment_id = a.equipment_id WHERE a.assignment_id = ?", assignmentId);
        if (rows.isEmpty()) throw ApiException.notFound("Assignment not found.");
        Map<String, Object> a = new LinkedHashMap<>(rows.get(0));
        a.put("containers", jdbc.queryForList("""
            SELECT ac.cart_position, ac.put_check_digits, o.order_number, c.code AS customer, z.temp_zone::text AS temp_zone, ac.inducted_lpn, ct.barcode AS tote, z.dropped_at
            FROM assignment_container ac JOIN zone_order z ON z.zone_order_id = ac.zone_order_id JOIN customer_order o ON o.order_id = z.order_id
            JOIN customer c ON c.customer_id = o.customer_id LEFT JOIN container ct ON ct.container_id = ac.container_id
            WHERE ac.assignment_id = ? ORDER BY ac.cart_position""", assignmentId));
        a.put("tasks", jdbc.queryForList("""
            SELECT t.task_id, t.seq, t.status::text AS status, i.sku::text AS sku, i.description, lf.code AS from_location, lt.code AS to_location,
                   inv.lpn, t.qty, t.check_digits, t.put_check_digits, t.cart_position, t.spoken_prompt, t.completed_at
            FROM assignment_task t LEFT JOIN item i ON i.item_id = t.item_id LEFT JOIN inventory inv ON inv.inventory_id = t.inventory_id
            LEFT JOIN location lf ON lf.location_id = t.from_location LEFT JOIN location lt ON lt.location_id = t.to_location
            WHERE t.assignment_id = ? ORDER BY t.seq""", assignmentId));
        return a;
    }

    /** Induct a tote or shipping LPN at a cart position (put-to-container by check digits). */
    @Transactional
    public void induct(UUID assignmentId, int position, String putDigits, String toteBarcode, String shippingLpn) {
        Map<String, Object> ac = jdbc.queryForMap("SELECT ac.assignment_container_id, ac.put_check_digits, a.site_id FROM assignment_container ac JOIN assignment a ON a.assignment_id = ac.assignment_id WHERE ac.assignment_id = ? AND ac.cart_position = ?", assignmentId, position);
        access.require((UUID) ac.get("site_id"), Capabilities.SELECTION_EXECUTE);
        if (putDigits == null || !putDigits.trim().equals(String.valueOf(ac.get("put_check_digits")).trim()))
            throw ApiException.conflict("Put check digit mismatch — hard stop.");
        UUID containerId = toteBarcode == null ? null : jdbc.queryForObject("SELECT container_id FROM container WHERE site_id = ? AND barcode = ?", UUID.class, ac.get("site_id"), toteBarcode.toUpperCase());
        jdbc.update("UPDATE assignment_container SET container_id = ?, inducted_lpn = ?, inducted_at = now() WHERE assignment_container_id = ?", containerId, shippingLpn == null ? null : shippingLpn.toUpperCase(), ac.get("assignment_container_id"));
    }

    /** {checkDigits, putCheckDigits, qty?} — qty below task qty is a short pick; the remainder stays open. */
    @Transactional
    public Map<String, Object> pick(UUID taskId, String checkDigits, String putDigits, BigDecimal qty) {
        Map<String, Object> t;
        try {
            t = jdbc.queryForMap("SELECT t.*, t.status::text AS status, a.site_id, a.assignment_type::text AS type FROM assignment_task t JOIN assignment a ON a.assignment_id = t.assignment_id WHERE t.task_id = ?", taskId);
        } catch (Exception e) { throw ApiException.notFound("Task not found."); }
        if (!"SELECTION".equals(t.get("type"))) throw ApiException.badRequest("Not a selection task.");
        access.require((UUID) t.get("site_id"), Capabilities.SELECTION_EXECUTE);
        if (!"OPEN".equals(t.get("status"))) throw ApiException.conflict("Task is not open.");
        if (checkDigits == null || !checkDigits.trim().equals(String.valueOf(t.get("check_digits")).trim()))
            throw ApiException.conflict("Slot check digit mismatch — hard stop. You're at the wrong location.");
        if (putDigits == null || !putDigits.trim().equals(String.valueOf(t.get("put_check_digits")).trim()))
            throw ApiException.conflict("Put check digit mismatch — wrong cart position.");
        BigDecimal taskQty = (BigDecimal) t.get("qty");
        BigDecimal picked = qty == null ? taskQty : qty;
        if (picked.signum() < 0 || picked.compareTo(taskQty) > 0) throw ApiException.badRequest("qty must be between 0 and " + taskQty.stripTrailingZeros().toPlainString() + ".");
        UUID invId = (UUID) t.get("inventory_id");
        jdbc.update("UPDATE inventory SET qty = qty - ?, status = CASE WHEN qty - ? = 0 THEN 'PICKED'::inventory_status ELSE status END, updated_at = now() WHERE inventory_id = ?", picked, picked, invId);
        jdbc.update("UPDATE allocation SET picked_qty = picked_qty + ? WHERE allocation_id = ?", picked, t.get("allocation_id"));
        jdbc.update("UPDATE customer_order_line ol SET picked_qty = (SELECT COALESCE(sum(picked_qty), 0) FROM allocation WHERE order_line_id = ol.order_line_id) WHERE ol.order_line_id = (SELECT order_line_id FROM allocation WHERE allocation_id = ?)", t.get("allocation_id"));
        jdbc.update("INSERT INTO inventory_movement (inventory_id, from_location, qty, movement_type, performed_by, assignment_id) VALUES (?, ?, ?, 'PICK', ?, ?)", invId, t.get("from_location"), picked, TenantContext.user(), t.get("assignment_id"));
        boolean shorted = picked.compareTo(taskQty) < 0;
        if (shorted) {
            // release the unpicked remainder so it can be re-allocated elsewhere
            jdbc.update("UPDATE allocation SET qty = picked_qty WHERE allocation_id = ? AND picked_qty > 0", t.get("allocation_id"));
            jdbc.update("DELETE FROM allocation WHERE allocation_id = ? AND picked_qty = 0", t.get("allocation_id"));
            jdbc.update("UPDATE customer_order_line ol SET allocated_qty = (SELECT COALESCE(sum(qty), 0) FROM allocation WHERE order_line_id = ol.order_line_id) WHERE ol.order_id IN (SELECT z.order_id FROM zone_order z JOIN assignment_container ac ON ac.zone_order_id = z.zone_order_id WHERE ac.assignment_id = ?)", t.get("assignment_id"));
        }
        jdbc.update("UPDATE assignment_task SET status = 'COMPLETE', completed_at = now(), qty = ? WHERE task_id = ?", picked, taskId);
        jdbc.update("UPDATE assignment SET assigned_to = COALESCE(assigned_to, ?), status = CASE WHEN status IN ('OPEN','ASSIGNED') THEN 'IN_PROGRESS'::assignment_status ELSE status END WHERE assignment_id = ?", TenantContext.user(), t.get("assignment_id"));
        // order status follows its zone orders
        jdbc.update("""
            UPDATE customer_order o SET status = CASE
                WHEN NOT EXISTS (SELECT 1 FROM customer_order_line ol WHERE ol.order_id = o.order_id AND ol.picked_qty < ol.allocated_qty) THEN 'PICKED'::order_status
                ELSE 'PICKING'::order_status END, updated_at = now()
            WHERE o.status IN ('ALLOCATED','PICKING') AND o.order_id IN (SELECT z.order_id FROM zone_order z JOIN assignment_container ac ON ac.zone_order_id = z.zone_order_id WHERE ac.assignment_id = ?)""", t.get("assignment_id"));
        jdbc.update("UPDATE wave w SET status = CASE WHEN EXISTS (SELECT 1 FROM assignment a WHERE a.wave_id = w.wave_id AND a.status NOT IN ('COMPLETE','CANCELLED')) THEN 'PICKING'::wave_status ELSE 'COMPLETE'::wave_status END, completed_at = CASE WHEN EXISTS (SELECT 1 FROM assignment a WHERE a.wave_id = w.wave_id AND a.status NOT IN ('COMPLETE','CANCELLED')) THEN NULL ELSE now() END WHERE w.wave_id = (SELECT wave_id FROM assignment WHERE assignment_id = ?)", t.get("assignment_id"));
        return Map.of("taskId", taskId, "picked", picked, "shorted", shorted);
    }

    /** Drop every container of the assignment at a drop location; orders become DROPPED once all their zones are down. */
    @Transactional
    public Map<String, Object> drop(UUID assignmentId, String dropLocation) {
        Map<String, Object> a = jdbc.queryForMap("SELECT site_id, status::text AS status FROM assignment WHERE assignment_id = ?", assignmentId);
        UUID siteId = (UUID) a.get("site_id");
        access.require(siteId, Capabilities.SELECTION_EXECUTE);
        if (!"COMPLETE".equals(a.get("status"))) throw ApiException.conflict("Finish the picks before dropping (assignment is " + a.get("status") + ").");
        UUID loc = locations.idByCode(siteId, dropLocation);
        String type = jdbc.queryForObject("SELECT loc_type::text FROM location WHERE location_id = ?", String.class, loc);
        if (!List.of("DROP", "STAGING", "SHIPPING_DOCK").contains(type)) throw ApiException.badRequest(dropLocation + " is a " + type + ", not a drop/staging/dock location.");
        int n = jdbc.update("UPDATE zone_order SET dropped_at = now(), drop_location_id = ? WHERE assignment_id = ? AND dropped_at IS NULL", loc, assignmentId);
        jdbc.update("INSERT INTO inventory_movement (inventory_id, to_location, qty, movement_type, performed_by, assignment_id) SELECT DISTINCT t.inventory_id, ?, 0, 'DROP', ?, t.assignment_id FROM assignment_task t WHERE t.assignment_id = ? AND t.status = 'COMPLETE'", loc, TenantContext.user(), assignmentId);
        jdbc.update("UPDATE customer_order o SET status = 'DROPPED', updated_at = now() WHERE o.status = 'PICKED' AND o.order_id IN (SELECT z.order_id FROM zone_order z WHERE z.assignment_id = ?) AND NOT EXISTS (SELECT 1 FROM zone_order z2 WHERE z2.order_id = o.order_id AND z2.dropped_at IS NULL)", assignmentId);
        return Map.of("assignmentId", assignmentId, "dropped", n, "location", dropLocation.toUpperCase());
    }
}
