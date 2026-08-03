package com.artemis.wms.service;

import com.artemis.wms.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * M5 — Replenishment. The trigger-scan job eats from v_replen_pressure and
 * generates slot-to-slot moves: pick face at/below trigger -> find the best
 * reserve LPN for the face's dedicated item (same rotation logic as
 * allocation, so FEFO faces get FEFO reserve) -> REPLENISHMENT assignment
 * with both sides check-digit verified. Critically-low faces (at/below half
 * of trigger) ring the bell via a CRITICAL REPLEN_CRITICAL alert, which the
 * V8 trigger also escalates to the email outbox.
 */
@Service
public class ReplenishmentService {

    private final JdbcTemplate jdbc;
    private final AlertService alerts;
    private final PolicyService policy;

    public ReplenishmentService(JdbcTemplate jdbc, AlertService alerts, PolicyService policy) {
        this.jdbc = jdbc; this.alerts = alerts; this.policy = policy;
    }

    /** Scheduled scan across all sites; also callable on demand per site. */
    @Scheduled(fixedDelayString = "${artemis.replen.scan-fixed-delay-ms:60000}")
    public void scheduledScan() {
        List<UUID> sites = jdbc.queryForList(
            "SELECT DISTINCT site_id FROM v_replen_pressure", UUID.class);
        for (UUID site : sites) {
            try { scan(site); } catch (Exception ignored) { /* next site; scan re-runs in a minute */ }
        }
    }

    @Transactional
    public Map<String, Object> scan(UUID siteId) {
        List<Map<String, Object>> pressure = jdbc.queryForList(
            "SELECT site_id, location_id, location_code, replen_item_id, sku::text AS sku, on_hand, replen_trigger_qty, replen_max_qty FROM v_replen_pressure WHERE site_id = ?", siteId);
        List<UUID> created = new ArrayList<>();
        int alertsRaised = 0;

        for (Map<String, Object> face : pressure) {
            UUID faceId = (UUID) face.get("location_id");
            UUID itemId = (UUID) face.get("replen_item_id");

            // one open replen per face — don't stack duplicate work
            Integer already = jdbc.queryForObject("""
                SELECT count(*) FROM assignment a JOIN assignment_task t ON t.assignment_id = a.assignment_id
                WHERE a.assignment_type = 'REPLENISHMENT' AND a.status IN ('OPEN','ASSIGNED','IN_PROGRESS')
                  AND t.to_location = ?
                """, Integer.class, faceId);
            boolean hasOpen = already != null && already > 0;

            BigDecimal onHand = (BigDecimal) face.get("on_hand");
            BigDecimal trigger = (BigDecimal) face.get("replen_trigger_qty");
            BigDecimal maxQty = (BigDecimal) face.get("replen_max_qty");

            // critically low: at/below half of trigger -> CRITICAL alert (bell + email outbox via V8)
            if (trigger != null && onHand.compareTo(trigger.divide(new BigDecimal(2), 3, java.math.RoundingMode.HALF_UP)) <= 0) {
                Integer openCritical = jdbc.queryForObject("""
                    SELECT count(*) FROM system_alert
                    WHERE alert_type = 'REPLEN_CRITICAL' AND inventory_id IS NULL AND item_id = ?
                      AND site_id = ? AND acknowledged_at IS NULL
                    """, Integer.class, itemId, siteId);
                if (openCritical == null || openCritical == 0) {
                    UUID areaId = jdbc.queryForObject(
                        "SELECT area_id FROM location WHERE location_id = ?", UUID.class, faceId);
                    alerts.raise(siteId, areaId, "REPLEN_CRITICAL", "CRITICAL",
                        "Pick face " + face.get("location_code") + " (" + face.get("sku")
                            + ") critically low: " + onHand.stripTrailingZeros().toPlainString()
                            + " on hand against trigger " + trigger.stripTrailingZeros().toPlainString()
                            + ". Replenish before the next wave releases.",
                        null, itemId, null);
                    alertsRaised++;
                }
            }
            if (hasOpen) continue;

            // reserve candidate: rotation-ordered, storage only, not the face itself
            String rotation = policy.effectiveRotation(itemId, siteId);
            String orderBy = switch (rotation) {
                case "FEFO" -> "expiration_date ASC NULLS LAST";
                case "LIFO" -> "arrival_date DESC NULLS LAST";
                default     -> "arrival_date ASC NULLS LAST";
            };
            List<Map<String, Object>> reserve = jdbc.queryForList("""
                SELECT v.inventory_id, v.lpn, v.available_qty, v.location_id, v.location_code, v.check_digits
                FROM v_available_inventory v
                JOIN location l ON l.location_id = v.location_id
                WHERE v.site_id = ? AND v.item_id = ? AND l.loc_type = 'STORAGE'
                ORDER BY """ + " " + orderBy + " LIMIT 1", siteId, itemId);
            if (reserve.isEmpty()) continue;    // nothing in reserve — the pressure view keeps it visible
            Map<String, Object> src = reserve.get(0);

            BigDecimal need = maxQty == null ? (BigDecimal) src.get("available_qty")
                    : maxQty.subtract(onHand).min((BigDecimal) src.get("available_qty"));
            if (need.signum() <= 0) continue;

            Map<String, Object> faceLoc = jdbc.queryForMap(
                "SELECT code, check_digits FROM location WHERE location_id = ?", faceId);

            UUID assignmentId = UUID.randomUUID();
            UUID corp = jdbc.queryForObject(
                "SELECT corporation_id FROM location WHERE location_id = ?", UUID.class, faceId);
            jdbc.update("""
                INSERT INTO assignment (assignment_id, corporation_id, site_id, assignment_type, priority)
                VALUES (?, ?, ?, 'REPLENISHMENT', 70)
                """, assignmentId, corp, siteId);
            String prompt = "Replenish: pull from " + String.valueOf(src.get("location_code")).replace("-", " ")
                    + ", check " + src.get("check_digits") + " — put to "
                    + String.valueOf(faceLoc.get("code")).replace("-", " ")
                    + ", check " + faceLoc.get("check_digits");
            jdbc.update("""
                INSERT INTO assignment_task (assignment_id, seq, inventory_id, item_id, from_location,
                    to_location, qty, check_digits, put_check_digits, spoken_prompt)
                VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
                """, assignmentId, src.get("inventory_id"), itemId, src.get("location_id"),
                faceId, need, src.get("check_digits"), faceLoc.get("check_digits"), prompt);
            created.add(assignmentId);
        }
        return Map.of("siteId", siteId, "assignmentsCreated", created, "criticalAlerts", alertsRaised);
    }

    public List<Map<String, Object>> openTasks(UUID siteId) {
        return jdbc.queryForList("""
            SELECT t.task_id, a.assignment_id, i.lpn, it.sku::text AS sku, lf.code AS from_code, lt.code AS to_code,
                   t.qty, t.check_digits, t.put_check_digits, t.spoken_prompt
            FROM assignment_task t
            JOIN assignment a ON a.assignment_id = t.assignment_id
            LEFT JOIN inventory i ON i.inventory_id = t.inventory_id
            LEFT JOIN item it ON it.item_id = t.item_id
            LEFT JOIN location lf ON lf.location_id = t.from_location
            LEFT JOIN location lt ON lt.location_id = t.to_location
            WHERE a.site_id = ? AND a.assignment_type = 'REPLENISHMENT' AND t.status = 'OPEN'
            ORDER BY a.priority DESC, a.created_at
            """, siteId);
    }

    /** Both sides verified, same discipline as putaway/selection. Splits the reserve LPN if partial. */
    @Transactional
    public void complete(UUID taskId, String pickDigits, String putDigits) {
        Map<String, Object> t = jdbc.queryForMap("""
            SELECT assignment_id, inventory_id, to_location, qty, check_digits, put_check_digits,
                   status::text AS status
            FROM assignment_task WHERE task_id = ?
            """, taskId);
        if (!"OPEN".equals(t.get("status"))) throw new com.artemis.wms.common.ApiException(
            org.springframework.http.HttpStatus.CONFLICT, "Task is not open.");
        if (pickDigits == null || !pickDigits.equals(t.get("check_digits")))
            throw com.artemis.wms.common.ApiException.conflict("Pull-side check digit mismatch.");
        if (putDigits == null || !putDigits.equals(t.get("put_check_digits")))
            throw com.artemis.wms.common.ApiException.conflict("Face-side check digit mismatch.");

        UUID invId = (UUID) t.get("inventory_id");
        BigDecimal moveQty = (BigDecimal) t.get("qty");
        BigDecimal invQty = jdbc.queryForObject(
            "SELECT qty FROM inventory WHERE inventory_id = ?", BigDecimal.class, invId);
        UUID moved = invId;
        if (invQty != null && moveQty.compareTo(invQty) < 0) {
            moved = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO inventory (inventory_id, corporation_id, site_id, lpn, item_id, location_id,
                    qty, lot_number, expiration_date, arrival_date, actual_weight_kg)
                SELECT ?, corporation_id, site_id, lpn || '-R' || substr(?::text, 1, 4), item_id, ?,
                    ?, lot_number, expiration_date, arrival_date, actual_weight_kg
                FROM inventory WHERE inventory_id = ?
                """, moved, moved, t.get("to_location"), moveQty, invId);
            jdbc.update("UPDATE inventory SET qty = qty - ?, updated_at = now() WHERE inventory_id = ?",
                moveQty, invId);
        } else {
            jdbc.update("UPDATE inventory SET location_id = ?, updated_at = now() WHERE inventory_id = ?",
                t.get("to_location"), invId);
        }
        jdbc.update("""
            INSERT INTO inventory_movement (inventory_id, from_location, to_location, qty, movement_type,
                performed_by, assignment_id)
            SELECT ?, from_location, to_location, ?, 'REPLENISH', ?, assignment_id
            FROM assignment_task WHERE task_id = ?
            """, moved, moveQty, TenantContext.user(), taskId);
        jdbc.update("UPDATE assignment_task SET status = 'COMPLETE', completed_at = now() WHERE task_id = ?", taskId);
        jdbc.update("""
            UPDATE assignment SET status = 'COMPLETE', completed_at = now()
            WHERE assignment_id = ? AND NOT EXISTS
                (SELECT 1 FROM assignment_task WHERE assignment_id = ? AND status <> 'COMPLETE')
            """, t.get("assignment_id"), t.get("assignment_id"));
    }
}
