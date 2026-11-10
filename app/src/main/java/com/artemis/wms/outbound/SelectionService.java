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
 * PICKED, never deleted, so the journal stays whole. What was picked does
 * not vanish: it becomes a pick unit (PK-n) riding on the equipment in its
 * put position until the load is dropped, then sits at the drop location
 * until its shipment leaves.
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
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.*, a.assignment_type::text AS assignment_type, a.status::text AS status, a.temp_zone::text AS temp_zone, w.wave_number, e.code AS equipment, u.display_name AS operator, m.manifest_number FROM assignment a LEFT JOIN wave w ON w.wave_id = a.wave_id LEFT JOIN equipment e ON e.equipment_id = a.equipment_id LEFT JOIN app_user u ON u.user_id = a.assigned_to LEFT JOIN receiving_manifest m ON m.manifest_id = a.manifest_id WHERE a.assignment_id = ?", assignmentId);
        if (rows.isEmpty()) throw ApiException.notFound("Assignment not found.");
        Map<String, Object> a = new LinkedHashMap<>(rows.get(0));
        a.put("containers", jdbc.queryForList("""
            SELECT ac.cart_position, ac.put_check_digits, o.order_number, c.code AS customer, c.name AS customer_name, z.temp_zone::text AS temp_zone, ac.inducted_lpn, ct.barcode AS tote, z.dropped_at,
                   dl.code AS drop_location,
                   (SELECT COALESCE(sum(t.qty), 0) FROM assignment_task t WHERE t.assignment_id = ac.assignment_id AND t.cart_position = ac.cart_position AND t.status = 'COMPLETE') AS picked_qty
            FROM assignment_container ac JOIN zone_order z ON z.zone_order_id = ac.zone_order_id JOIN customer_order o ON o.order_id = z.order_id
            JOIN customer c ON c.customer_id = o.customer_id LEFT JOIN container ct ON ct.container_id = ac.container_id
            LEFT JOIN location dl ON dl.location_id = z.drop_location_id
            WHERE ac.assignment_id = ? ORDER BY ac.cart_position""", assignmentId));
        a.put("tasks", jdbc.queryForList("""
            SELECT t.task_id, t.seq, t.status::text AS status, i.sku::text AS sku, i.description, lf.code AS from_location, lt.code AS to_location,
                   inv.lpn, inv.lot_number, t.qty, t.check_digits, t.put_check_digits, t.cart_position, t.spoken_prompt, t.completed_at,
                   (SELECT o.order_number FROM allocation al JOIN customer_order_line ol ON ol.order_line_id = al.order_line_id JOIN customer_order o ON o.order_id = ol.order_id WHERE al.allocation_id = t.allocation_id) AS order_number
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
            t = jdbc.queryForMap("SELECT t.*, t.status::text AS status, a.site_id, a.assignment_type::text AS type, a.equipment_id FROM assignment_task t JOIN assignment a ON a.assignment_id = t.assignment_id WHERE t.task_id = ?", taskId);
        } catch (Exception e) { throw ApiException.notFound("Task not found."); }
        if (!"SELECTION".equals(t.get("type"))) throw ApiException.badRequest("Not a selection task.");
        access.require((UUID) t.get("site_id"), Capabilities.SELECTION_EXECUTE);
        if (!"OPEN".equals(t.get("status"))) throw ApiException.conflict("Task is not open.");
        // There is nowhere to pick TO until the unit is known.
        if (t.get("equipment_id") == null) throw ApiException.conflict("No unit on this assignment yet — choose the cart or truck you're picking to, then pick.");
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
        String pickUnit = null;
        if (picked.signum() > 0) {
            // The picked quantity leaves the slot and rides on the unit, in its position, for its zone order.
            Map<String, Object> unit = jdbc.queryForMap("""
                INSERT INTO inventory (corporation_id, site_id, lpn, item_id, location_id, qty, original_qty, status, lot_number, expiration_date, arrival_date,
                                       manifest_id, equipment_id, cart_position, picked_from, zone_order_id)
                SELECT v.corporation_id, v.site_id, 'PK-' || nextval('pick_unit_seq'), v.item_id, NULL, ?, ?, 'PICKED', v.lot_number, v.expiration_date, v.arrival_date,
                       v.manifest_id, ?, ?, v.inventory_id,
                       (SELECT ac.zone_order_id FROM assignment_container ac WHERE ac.assignment_id = ? AND ac.cart_position = ?)
                FROM inventory v WHERE v.inventory_id = ? RETURNING inventory_id, lpn""",
                picked, picked, t.get("equipment_id"), t.get("cart_position"), t.get("assignment_id"), t.get("cart_position"), invId);
            pickUnit = (String) unit.get("lpn");
            jdbc.update("INSERT INTO inventory_movement (inventory_id, from_location, qty, movement_type, performed_by, assignment_id) VALUES (?, ?, ?, 'PICK', ?, ?)", unit.get("inventory_id"), t.get("from_location"), picked, TenantContext.user(), t.get("assignment_id"));
        }
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
        // A wave is done when every assignment is, and nothing released is still waiting to be built.
        jdbc.update("""
            UPDATE wave w SET status = CASE WHEN x.open THEN 'PICKING'::wave_status ELSE 'COMPLETE'::wave_status END,
                              completed_at = CASE WHEN x.open THEN NULL ELSE now() END
            FROM (SELECT wv.wave_id,
                         EXISTS (SELECT 1 FROM assignment a WHERE a.wave_id = wv.wave_id AND a.status NOT IN ('COMPLETE','CANCELLED'))
                      OR EXISTS (SELECT 1 FROM zone_order z WHERE z.wave_id = wv.wave_id AND z.assignment_id IS NULL) AS open
                  FROM wave wv WHERE wv.wave_id = (SELECT wave_id FROM assignment WHERE assignment_id = ?)) x
            WHERE w.wave_id = x.wave_id""", t.get("assignment_id"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId); out.put("picked", picked); out.put("shorted", shorted); out.put("pickUnit", pickUnit);
        return out;
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
        // Everything riding on the unit for this assignment comes off it, onto the drop location.
        jdbc.update("""
            INSERT INTO inventory_movement (inventory_id, to_location, qty, movement_type, performed_by, assignment_id)
            SELECT v.inventory_id, ?, v.qty, 'DROP', ?, ? FROM inventory v
            WHERE v.picked_from IS NOT NULL AND v.location_id IS NULL AND v.status = 'PICKED'
              AND v.zone_order_id IN (SELECT ac.zone_order_id FROM assignment_container ac WHERE ac.assignment_id = ?)""", loc, TenantContext.user(), assignmentId, assignmentId);
        jdbc.update("""
            UPDATE inventory v SET location_id = ?, equipment_id = NULL, cart_position = NULL, updated_at = now()
            WHERE v.picked_from IS NOT NULL AND v.location_id IS NULL AND v.status = 'PICKED'
              AND v.zone_order_id IN (SELECT ac.zone_order_id FROM assignment_container ac WHERE ac.assignment_id = ?)""", loc, assignmentId);
        jdbc.update("UPDATE customer_order o SET status = 'DROPPED', updated_at = now() WHERE o.status = 'PICKED' AND o.order_id IN (SELECT z.order_id FROM zone_order z WHERE z.assignment_id = ?) AND NOT EXISTS (SELECT 1 FROM zone_order z2 WHERE z2.order_id = o.order_id AND z2.dropped_at IS NULL)", assignmentId);
        return Map.of("assignmentId", assignmentId, "dropped", n, "location", dropLocation.toUpperCase());
    }

    /**
     * Say which unit an assignment is picked to. Until this is known there is
     * no put position to verify against, so nothing can be picked. Changing
     * units is fine until the first pick lands on one.
     */
    @Transactional
    public Map<String, Object> setEquipment(UUID assignmentId, String equipmentCode) {
        Map<String, Object> a;
        try { a = jdbc.queryForMap("SELECT site_id, status::text AS status, assignment_type::text AS type, equipment_id, assignment_number FROM assignment WHERE assignment_id = ?", assignmentId); }
        catch (Exception e) { throw ApiException.notFound("Assignment not found."); }
        UUID siteId = (UUID) a.get("site_id");
        access.require(siteId, Capabilities.SELECTION_EXECUTE);
        if (!"SELECTION".equals(a.get("type"))) throw ApiException.badRequest("Only selection assignments are picked to a unit.");
        if (List.of("COMPLETE", "CANCELLED").contains(a.get("status"))) throw ApiException.conflict("Assignment is " + a.get("status") + ".");
        if (equipmentCode == null || equipmentCode.isBlank()) throw ApiException.badRequest("equipment is required.");
        Map<String, Object> eq;
        try { eq = jdbc.queryForMap("SELECT equipment_id, code, container_positions, active FROM equipment WHERE site_id = ? AND code = ?", siteId, equipmentCode.trim().toUpperCase()); }
        catch (Exception e) { throw ApiException.notFound("No unit " + equipmentCode.toUpperCase() + " at this site."); }
        if (!Boolean.TRUE.equals(eq.get("active"))) throw ApiException.conflict(eq.get("code") + " is out of service.");
        UUID equipmentId = (UUID) eq.get("equipment_id");
        if (equipmentId.equals(a.get("equipment_id"))) return Map.of("assignmentId", assignmentId, "equipment", eq.get("code"), "changed", false);
        Integer done = jdbc.queryForObject("SELECT count(*) FROM assignment_task WHERE assignment_id = ? AND status = 'COMPLETE' AND qty > 0", Integer.class, assignmentId);
        if (a.get("equipment_id") != null && done != null && done > 0) throw ApiException.conflict("Picks are already riding on the current unit — drop the load before changing units.");
        Integer orders = jdbc.queryForObject("SELECT count(*) FROM assignment_container WHERE assignment_id = ?", Integer.class, assignmentId);
        Integer positions = (Integer) eq.get("container_positions");
        if (positions != null && orders != null && orders > positions)
            throw ApiException.conflict(eq.get("code") + " has " + positions + " positions; this assignment carries " + orders + " zone orders.");
        jdbc.update("UPDATE assignment SET equipment_id = ?, updated_at = now() WHERE assignment_id = ?", equipmentId, assignmentId);
        // Put check digits belong to the unit's positions; the prompt ends with them.
        jdbc.update("""
            UPDATE assignment_container ac SET put_check_digits = COALESCE(
                (SELECT ep.check_digits FROM equipment_position ep WHERE ep.equipment_id = ? AND ep.position_no = ac.cart_position), ac.put_check_digits)
            WHERE ac.assignment_id = ?""", equipmentId, assignmentId);
        jdbc.update("""
            UPDATE assignment_task t SET put_check_digits = ac.put_check_digits,
                   spoken_prompt = regexp_replace(t.spoken_prompt, 'check [0-9]{2}$', 'check ' || ac.put_check_digits)
            FROM assignment_container ac
            WHERE ac.assignment_id = t.assignment_id AND ac.cart_position = t.cart_position AND t.assignment_id = ? AND t.status = 'OPEN'""", assignmentId);
        return Map.of("assignmentId", assignmentId, "equipment", eq.get("code"), "changed", true);
    }

    /** If the operator has exactly one unit checked out, that is the unit they're picking to. */
    @Transactional
    public String adoptCheckedOutUnit(UUID assignmentId, UUID userId) {
        Map<String, Object> a = jdbc.queryForMap("SELECT site_id, equipment_id, assignment_type::text AS type FROM assignment WHERE assignment_id = ?", assignmentId);
        if (!"SELECTION".equals(a.get("type")) || a.get("equipment_id") != null || userId == null) return null;
        List<String> mine = jdbc.queryForList("""
            SELECT e.code FROM equipment_checkout co JOIN equipment e ON e.equipment_id = co.equipment_id
            WHERE co.user_id = ? AND co.checked_in_at IS NULL AND e.site_id = ? AND e.active
              AND (e.container_positions IS NULL OR e.container_positions >= (SELECT count(*) FROM assignment_container WHERE assignment_id = ?))""",
            String.class, userId, a.get("site_id"), assignmentId);
        if (mine.size() != 1) return null;
        setEquipment(assignmentId, mine.get(0));
        return mine.get(0);
    }
}
