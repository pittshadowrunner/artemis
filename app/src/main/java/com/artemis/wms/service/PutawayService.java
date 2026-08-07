package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PutawayService {

    private final JdbcTemplate jdbc;
    private final PolicyService policy;

    public PutawayService(JdbcTemplate jdbc, PolicyService policy) {
        this.jdbc = jdbc; this.policy = policy;
    }

    /**
     * directed_putaway_slot is the single source of truth so the API, the
     * voice dialog, and any future slotting simulator agree on what "the
     * right slot" means. No qualifying slot is a hard error, not a silent
     * dump to overflow.
     */
    @Transactional
    public Map<String, Object> createTask(UUID siteId, UUID inventoryId, UUID itemId, String lot, UUID fromLocation) {
        List<Map<String, Object>> slots = jdbc.queryForList(
            "SELECT * FROM directed_putaway_slot(?, ?, ?, ?, ?)",
            siteId, itemId, lot, policy.allowItemMixing(siteId), policy.allowLotMixing(siteId));
        if (slots.isEmpty())
            throw ApiException.conflict("No qualifying storage slot for this item (temp zone / hazmat / mixing rules).");
        Map<String, Object> slot = slots.get(0);

        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO assignment (assignment_id, corporation_id, site_id, assignment_type, assignment_number)
            VALUES (?, ?, ?, 'PUTAWAY', 'A-' || to_char(now(),'YYMMDD') || '-' || lpad(nextval('assignment_number_seq')::text, 5, '0'))
            """, assignmentId, TenantContext.corp(), siteId);
        UUID taskId = UUID.randomUUID();
        String digits = String.valueOf(slot.get("check_digits"));
        String prompt = "Put away to " + String.valueOf(slot.get("code")).replace("-", " ") + ", check " + digits;
        jdbc.update("""
            INSERT INTO assignment_task (task_id, assignment_id, seq, inventory_id, item_id,
                from_location, to_location, qty, check_digits, spoken_prompt)
            SELECT ?, ?, 1, ?, ?, ?, ?, i.qty, ?, ?
            FROM inventory i WHERE i.inventory_id = ?
            """, taskId, assignmentId, inventoryId, itemId, fromLocation,
            slot.get("location_id"), digits, prompt, inventoryId);

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("assignmentId", assignmentId);
        task.put("taskId", taskId);
        task.put("destination", slot.get("code"));
        task.put("checkDigits", digits);
        task.put("spokenPrompt", prompt);
        return task;
    }

    public List<Map<String, Object>> openTasks(UUID siteId) {
        return jdbc.queryForList("""
            SELECT t.task_id, a.assignment_id, a.priority, i.lpn, it.sku::text AS sku, it.description,
                   lf.code AS from_code, lt.code AS to_code, t.qty, t.check_digits, t.spoken_prompt
            FROM assignment_task t
            JOIN assignment a ON a.assignment_id = t.assignment_id
            LEFT JOIN inventory i ON i.inventory_id = t.inventory_id
            LEFT JOIN item it ON it.item_id = t.item_id
            LEFT JOIN location lf ON lf.location_id = t.from_location
            LEFT JOIN location lt ON lt.location_id = t.to_location
            WHERE a.site_id = ? AND a.assignment_type = 'PUTAWAY' AND t.status = 'OPEN'
            ORDER BY a.priority DESC, a.created_at
            """, siteId);
    }

    /**
     * Check-digit verified completion. Supervisor overrides to a different
     * slot require a reason and are audit-logged — putting a pallet in the
     * wrong slot silently is how inventory accuracy dies.
     */
    @Transactional
    public void complete(UUID taskId, String spokenDigits, UUID overrideLocationId, String overrideReason) {
        Map<String, Object> t = jdbc.queryForMap("""
            SELECT t.assignment_id, t.inventory_id, t.to_location, t.check_digits, t.qty, t.status::text AS status
            FROM assignment_task t WHERE t.task_id = ?
            """, taskId);
        if (!"OPEN".equals(t.get("status"))) throw ApiException.conflict("Task is not open.");

        UUID destination = (UUID) t.get("to_location");
        if (overrideLocationId != null) {
            if (overrideReason == null || overrideReason.isBlank())
                throw ApiException.badRequest("Supervisor override requires a reason.");
            destination = overrideLocationId;
        } else {
            if (spokenDigits == null || !spokenDigits.equals(t.get("check_digits")))
                throw ApiException.conflict("Check digit mismatch — hard stop. Verify the slot and try again.");
        }

        jdbc.update("UPDATE inventory SET location_id = ?, updated_at = now() WHERE inventory_id = ?",
            destination, t.get("inventory_id"));
        jdbc.update("""
            INSERT INTO inventory_movement (inventory_id, from_location, to_location, qty, movement_type,
                performed_by, assignment_id)
            SELECT inventory_id, from_location, ?, qty,
                   CASE WHEN ? THEN 'PUTAWAY_OVERRIDE' ELSE 'PUTAWAY' END, ?, assignment_id
            FROM assignment_task WHERE task_id = ?
            """, destination, overrideLocationId != null, TenantContext.user(), taskId);
        jdbc.update("UPDATE assignment_task SET status = 'COMPLETE', completed_at = now() WHERE task_id = ?", taskId);
        jdbc.update("""
            UPDATE assignment SET status = 'COMPLETE', completed_at = now()
            WHERE assignment_id = ? AND NOT EXISTS
                (SELECT 1 FROM assignment_task WHERE assignment_id = ? AND status <> 'COMPLETE')
            """, t.get("assignment_id"), t.get("assignment_id"));
    }
}
