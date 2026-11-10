package com.artemis.wms.inbound;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.masterdata.LocationService;
import com.artemis.wms.org.PolicyService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Directed putaway. directed_putaway_slot() is the single source of truth so
 * the API, the voice dialog and any future slotting simulator agree. No
 * qualifying slot is a hard error, never a silent dump to overflow.
 */
@Service
public class PutawayService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final PolicyService policy;
    private final LocationService locations;

    public PutawayService(JdbcTemplate jdbc, AccessService access, PolicyService policy, LocationService locations) {
        this.jdbc = jdbc; this.access = access; this.policy = policy; this.locations = locations;
    }

    @Transactional
    public Map<String, Object> createTask(UUID siteId, UUID inventoryId, UUID itemId, String lot, UUID fromLocation) {
        return createTask(siteId, inventoryId, itemId, lot, fromLocation, null);
    }

    @Transactional
    public Map<String, Object> createTask(UUID siteId, UUID inventoryId, UUID itemId, String lot, UUID fromLocation, UUID manifestId) {
        List<Map<String, Object>> slots = jdbc.queryForList("SELECT * FROM directed_putaway_slot(?, ?, ?, ?, ?)",
            siteId, itemId, lot, policy.allowItemMixing(siteId), policy.allowLotMixing(siteId));
        if (slots.isEmpty()) throw ApiException.conflict("No open reserve slot qualifies for this item (temp zone, hazmat and mixing rules) — the pallet was not received. Free or add a slot in that zone, then receive again.");
        Map<String, Object> slot = slots.get(0);
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO assignment (assignment_id, corporation_id, site_id, assignment_number, assignment_type, priority, temp_zone, manifest_id)
            SELECT ?, v.corporation_id, ?, next_assignment_number(), 'PUTAWAY', default_priority('PUTAWAY', i.temp_zone), i.temp_zone, ?
            FROM inventory v JOIN item i ON i.item_id = v.item_id WHERE v.inventory_id = ?""", assignmentId, siteId, manifestId, inventoryId);
        UUID taskId = UUID.randomUUID();
        String digits = String.valueOf(slot.get("check_digits"));
        String prompt = "Put away to " + String.valueOf(slot.get("code")).replace("-", " ") + ", check " + digits;
        jdbc.update("""
            INSERT INTO assignment_task (task_id, assignment_id, seq, inventory_id, item_id, from_location, to_location, qty, check_digits, spoken_prompt)
            SELECT ?, ?, 1, ?, ?, ?, ?, qty, ?, ? FROM inventory WHERE inventory_id = ?""",
            taskId, assignmentId, inventoryId, itemId, fromLocation, slot.get("location_id"), digits, prompt, inventoryId);
        String number = jdbc.queryForObject("SELECT assignment_number FROM assignment WHERE assignment_id = ?", String.class, assignmentId);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("assignmentId", assignmentId); t.put("assignmentNumber", number); t.put("taskId", taskId);
        t.put("destination", slot.get("code")); t.put("checkDigits", digits); t.put("spokenPrompt", prompt);
        return t;
    }

    public List<Map<String, Object>> openTasks(UUID siteId) {
        return jdbc.queryForList("""
            SELECT t.task_id, a.assignment_id, a.assignment_number, a.priority, a.temp_zone::text AS temp_zone, a.status::text AS assignment_status,
                   u.display_name AS assigned_to, v.lpn, i.sku::text AS sku, i.description,
                   lf.code AS from_location, lt.code AS to_location, t.qty, t.check_digits, t.spoken_prompt,
                   now() - v.created_at AS dwell, a.created_at
            FROM assignment_task t
            JOIN assignment a ON a.assignment_id = t.assignment_id
            LEFT JOIN app_user u ON u.user_id = a.assigned_to
            LEFT JOIN inventory v ON v.inventory_id = t.inventory_id
            LEFT JOIN item i ON i.item_id = t.item_id
            LEFT JOIN location lf ON lf.location_id = t.from_location
            LEFT JOIN location lt ON lt.location_id = t.to_location
            WHERE a.site_id = ? AND a.assignment_type = 'PUTAWAY' AND t.status = 'OPEN'
            ORDER BY a.priority DESC, a.created_at""", siteId);
    }


    /**
     * Check-digit verified completion. A supervisor override to a different
     * slot requires a reason and is journaled as PUTAWAY_OVERRIDE — putting a
     * pallet in the wrong slot silently is how inventory accuracy dies.
     */
    @Transactional
    public Map<String, Object> complete(UUID taskId, String spokenDigits, String overrideLocation, String overrideReason) {
        Map<String, Object> t;
        try {
            t = jdbc.queryForMap("SELECT t.assignment_id, t.inventory_id, t.to_location, t.check_digits, t.status::text AS status, a.site_id FROM assignment_task t JOIN assignment a ON a.assignment_id = t.assignment_id WHERE t.task_id = ?", taskId);
        } catch (Exception e) { throw ApiException.notFound("Task not found."); }
        UUID siteId = (UUID) t.get("site_id");
        access.require(siteId, Capabilities.PUTAWAY_EXECUTE);
        if (!"OPEN".equals(t.get("status"))) throw ApiException.conflict("Task is not open.");
        UUID destination = (UUID) t.get("to_location");
        boolean override = overrideLocation != null;
        if (override) {
            if (overrideReason == null || overrideReason.isBlank()) throw ApiException.badRequest("Supervisor override requires a reason.");
            destination = locations.idByCode(siteId, overrideLocation);
        } else if (spokenDigits == null || !spokenDigits.trim().equals(String.valueOf(t.get("check_digits")).trim())) {
            throw ApiException.conflict("Check digit mismatch — hard stop. Verify the slot and try again.");
        }
        jdbc.update("UPDATE inventory SET location_id = ?, updated_at = now() WHERE inventory_id = ?", destination, t.get("inventory_id"));
        jdbc.update("""
            INSERT INTO inventory_movement (inventory_id, from_location, to_location, qty, movement_type, performed_by, assignment_id)
            SELECT inventory_id, from_location, ?, qty, ?, ?, assignment_id FROM assignment_task WHERE task_id = ?""",
            destination, override ? "PUTAWAY_OVERRIDE" : "PUTAWAY", TenantContext.user(), taskId);
        jdbc.update("UPDATE assignment_task SET status = 'COMPLETE', completed_at = now(), to_location = ? WHERE task_id = ?", destination, taskId);
        jdbc.update("UPDATE assignment SET assigned_to = COALESCE(assigned_to, ?) WHERE assignment_id = ?", TenantContext.user(), t.get("assignment_id"));
        String code = jdbc.queryForObject("SELECT code FROM location WHERE location_id = ?", String.class, destination);
        return Map.of("taskId", taskId, "location", code, "override", override);
    }
}
