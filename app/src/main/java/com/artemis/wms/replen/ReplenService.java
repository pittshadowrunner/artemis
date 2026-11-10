package com.artemis.wms.replen;

import com.artemis.wms.alerts.AlertService;
import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Replenishment is automatic: nobody presses a button. A scheduled scan
 * ({@link ReplenJob}) watches every face; the API can also ask for a scan.
 * Faces at or under trigger get a task from the rotation-
 * correct reserve pallet, sized to bring the face to max. An empty face
 * is CRITICAL (priority 9 + alert). Completion splits the pallet: a child
 * with the same lot/expiry lands at the face; the parent keeps the rest.
 */
@Service
public class ReplenService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final AlertService alerts;
    public ReplenService(JdbcTemplate jdbc, AccessService access, AlertService alerts) { this.jdbc = jdbc; this.access = access; this.alerts = alerts; }

    public List<Map<String, Object>> pressure(UUID siteId) {
        return jdbc.queryForList("SELECT * FROM v_replen_pressure WHERE site_id = ? AND on_hand + in_flight <= replen_trigger_qty ORDER BY (on_hand + in_flight) / NULLIF(replen_trigger_qty, 0), location_code", siteId);
    }

    /** On-demand scan (API). The scheduled scan does the same thing unattended. */
    @Transactional
    public Map<String, Object> run(UUID siteId) {
        access.require(siteId, Capabilities.REPLEN_EXECUTE);
        return cut(siteId, access.requireTenant());
    }

    /**
     * Create one REPLENISHMENT assignment per face under pressure. Idempotent:
     * work already in flight counts toward the face, so a second pass cuts
     * nothing new and repeats no alert.
     */
    @Transactional
    public Map<String, Object> cut(UUID siteId, UUID corp) {
        int created = 0, noSource = 0;
        List<String> tasks = new ArrayList<>();
        for (Map<String, Object> face : pressure(siteId)) {
            UUID faceId = (UUID) face.get("location_id");
            List<Map<String, Object>> src = jdbc.queryForList("SELECT * FROM replen_source(?)", faceId);
            BigDecimal onHand = (BigDecimal) face.get("on_hand"), inFlight = (BigDecimal) face.get("in_flight");
            boolean critical = onHand.signum() == 0 && inFlight.signum() == 0;
            if (src.isEmpty()) {
                noSource++;
                alerts.raiseOnce(siteId, (UUID) face.get("area_id"), "REPLEN_NO_SOURCE", critical ? "CRITICAL" : "WARNING",
                    "Face " + face.get("location_code") + " (" + face.get("sku") + ") is at " + onHand.stripTrailingZeros().toPlainString() + " with no reserve stock to pull from.", "ITEM", (UUID) face.get("item_id"));
                continue;
            }
            Map<String, Object> s = src.get(0);
            BigDecimal max = (BigDecimal) face.get("replen_max_qty");
            BigDecimal need = (max == null ? (BigDecimal) face.get("replen_trigger_qty") : max).subtract(onHand).subtract(inFlight);
            BigDecimal qty = need.min((BigDecimal) s.get("available_qty"));
            if (qty.signum() <= 0) continue;
            UUID assignmentId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO assignment (assignment_id, corporation_id, site_id, assignment_number, assignment_type, priority, temp_zone)
                VALUES (?, ?, ?, next_assignment_number(), 'REPLENISHMENT', default_priority('REPLENISHMENT', ?::temp_zone, ?), ?::temp_zone)""",
                assignmentId, corp, siteId, String.valueOf(face.get("temp_zone")), critical, String.valueOf(face.get("temp_zone")));
            String digits = jdbc.queryForObject("SELECT check_digits FROM location WHERE location_id = ?", String.class, faceId);
            String prompt = "Replenish " + qty.stripTrailingZeros().toPlainString() + " from " + s.get("location_code") + " LPN " + s.get("lpn") + " to " + String.valueOf(face.get("location_code")).replace("-", " ") + ", check " + digits;
            jdbc.update("""
                INSERT INTO assignment_task (assignment_id, seq, inventory_id, item_id, from_location, to_location, qty, check_digits, spoken_prompt)
                SELECT ?, 1, ?, ?, inv.location_id, ?, ?, ?, ? FROM inventory inv WHERE inv.inventory_id = ?""",
                assignmentId, s.get("inventory_id"), face.get("item_id"), faceId, qty, digits, prompt, s.get("inventory_id"));
            if (critical) alerts.raiseOnce(siteId, (UUID) face.get("area_id"), "REPLEN_CRITICAL", "CRITICAL",
                "Pick face " + face.get("location_code") + " (" + face.get("sku") + ") is empty — replenishment " + jdbc.queryForObject("SELECT assignment_number FROM assignment WHERE assignment_id = ?", String.class, assignmentId) + " cut at priority 9.", "ITEM", (UUID) face.get("item_id"));
            created++;
            tasks.add(prompt);
        }
        return Map.of("created", created, "noSource", noSource, "tasks", tasks);
    }

    /** Check-digit verified completion: split the reserve pallet, child lands on the face. */
    @Transactional
    public Map<String, Object> complete(UUID taskId, String checkDigits) {
        Map<String, Object> t;
        try {
            t = jdbc.queryForMap("SELECT t.*, t.status::text AS status, a.site_id, a.assignment_type::text AS type FROM assignment_task t JOIN assignment a ON a.assignment_id = t.assignment_id WHERE t.task_id = ?", taskId);
        } catch (Exception e) { throw ApiException.notFound("Task not found."); }
        if (!"REPLENISHMENT".equals(t.get("type"))) throw ApiException.badRequest("Not a replenishment task.");
        access.require((UUID) t.get("site_id"), Capabilities.REPLEN_EXECUTE);
        if (!"OPEN".equals(t.get("status"))) throw ApiException.conflict("Task is not open.");
        if (checkDigits == null || !checkDigits.trim().equals(String.valueOf(t.get("check_digits")).trim()))
            throw ApiException.conflict("Check digit mismatch — hard stop.");
        UUID parent = (UUID) t.get("inventory_id");
        BigDecimal qty = (BigDecimal) t.get("qty");
        UUID child = UUID.randomUUID();
        // whole pallet moves as itself; a partial moves as a child
        BigDecimal parentQty = jdbc.queryForObject("SELECT qty FROM inventory WHERE inventory_id = ?", BigDecimal.class, parent);
        String childLpn;
        if (parentQty.compareTo(qty) == 0) {
            child = parent;
            jdbc.update("UPDATE inventory SET location_id = ?, updated_at = now() WHERE inventory_id = ?", t.get("to_location"), parent);
            childLpn = jdbc.queryForObject("SELECT lpn FROM inventory WHERE inventory_id = ?", String.class, parent);
        } else {
            childLpn = jdbc.queryForObject("SELECT next_child_lpn(?)", String.class, parent);
            jdbc.update("""
                INSERT INTO inventory (inventory_id, corporation_id, site_id, lpn, item_id, location_id, qty, original_qty, lot_number, expiration_date, arrival_date, manifest_id, parent_inventory_id)
                SELECT ?, corporation_id, site_id, ?, item_id, ?, ?, ?, lot_number, expiration_date, arrival_date, manifest_id, inventory_id FROM inventory WHERE inventory_id = ?""",
                child, childLpn, t.get("to_location"), qty, qty, parent);
            jdbc.update("UPDATE inventory SET qty = qty - ?, updated_at = now() WHERE inventory_id = ?", qty, parent);
        }
        jdbc.update("INSERT INTO inventory_movement (inventory_id, from_location, to_location, qty, movement_type, performed_by, assignment_id) VALUES (?, ?, ?, ?, 'REPLENISH', ?, ?)", child, t.get("from_location"), t.get("to_location"), qty, TenantContext.user(), t.get("assignment_id"));
        jdbc.update("UPDATE assignment_task SET status = 'COMPLETE', completed_at = now() WHERE task_id = ?", taskId);
        jdbc.update("UPDATE assignment SET assigned_to = COALESCE(assigned_to, ?) WHERE assignment_id = ?", TenantContext.user(), t.get("assignment_id"));
        return Map.of("taskId", taskId, "lpnAtFace", childLpn, "qty", qty);
    }
}
