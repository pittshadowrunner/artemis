package com.artemis.wms.work;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.platform.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatch across every assignment type. Two gates, in order:
 *   1. the dispatcher's capability for that workflow (role-based)
 *   2. the operator's trained-workflow allowance (an untrained picker
 *      never sees selection work, however broad their role)
 * Reassignment is tracked on the assignment, never a silent overwrite.
 */
@Service
public class DispatchService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    public DispatchService(JdbcTemplate jdbc, AccessService access) { this.jdbc = jdbc; this.access = access; }

    public static String workflowOf(String assignmentType) {
        return switch (assignmentType) {
            case "RECEIVING" -> "RECEIVING";
            case "PUTAWAY" -> "PUTAWAY";
            case "REPLENISHMENT" -> "REPLENISHMENT";
            case "SELECTION" -> "SELECTION";
            default -> "SHIPPING";       // LOADING, SHIPPING, CYCLE_COUNT
        };
    }
    public static String capabilityOf(String assignmentType) {
        return switch (assignmentType) {
            case "RECEIVING" -> Capabilities.RECEIVING_EXECUTE;
            case "PUTAWAY" -> Capabilities.PUTAWAY_EXECUTE;
            case "REPLENISHMENT" -> Capabilities.REPLEN_EXECUTE;
            case "SELECTION" -> Capabilities.SELECTION_EXECUTE;
            default -> Capabilities.SHIPPING_EXECUTE;
        };
    }

    /** Operators at a site who may be handed work of this type: role capability AND trained allowance. */
    public List<Map<String, Object>> eligible(UUID siteId, String assignmentType) {
        return jdbc.queryForList("""
            SELECT DISTINCT u.user_id, u.display_name, u.email::text AS email,
                   (SELECT count(*) FROM assignment a WHERE a.assigned_to = u.user_id AND a.status IN ('ASSIGNED','IN_PROGRESS')) AS open_work
            FROM app_user u
            JOIN user_org_grant g ON g.user_id = u.user_id
            JOIN role_capability rc ON rc.role_id = g.role_id AND rc.capability = ?
            WHERE u.active AND ? = ANY(u.trained_workflows)
              AND g.org_node_id IN (SELECT org_node_id FROM org_ancestry(?))
            ORDER BY open_work, u.display_name""", capabilityOf(assignmentType), workflowOf(assignmentType), siteId);
    }

    @Transactional
    public Map<String, Object> assign(UUID assignmentId, UUID userId, Integer priority) {
        Map<String, Object> a;
        try { a = jdbc.queryForMap("SELECT site_id, assigned_to, status::text AS status, assignment_type::text AS type, assignment_number FROM assignment WHERE assignment_id = ?", assignmentId); }
        catch (Exception e) { throw ApiException.notFound("Assignment not found."); }
        String type = (String) a.get("type");
        UUID siteId = (UUID) a.get("site_id");
        access.require(siteId, capabilityOf(type));
        if (List.of("COMPLETE", "CANCELLED").contains(a.get("status"))) throw ApiException.conflict("Assignment is " + a.get("status") + ".");
        if (userId != null) {
            Map<String, Object> u;
            try { u = jdbc.queryForMap("SELECT display_name, trained_workflows FROM app_user WHERE user_id = ? AND active", userId); }
            catch (Exception e) { throw ApiException.notFound("Operator not found."); }
            String workflow = workflowOf(type);
            List<String> trained = jdbc.queryForList("SELECT unnest(trained_workflows) FROM app_user WHERE user_id = ?", String.class, userId);
            if (!trained.contains(workflow))
                throw ApiException.conflict(u.get("display_name") + " is not trained for " + workflow + " — allowances: " + String.join(", ", trained) + ".");
            Integer ok = jdbc.queryForObject("SELECT count(*) FROM effective_capabilities(?, ?) WHERE capability = ?", Integer.class, userId, siteId, capabilityOf(type));
            if (ok == null || ok == 0) throw ApiException.conflict(u.get("display_name") + " has no " + capabilityOf(type) + " at this site.");
        }
        boolean reassign = a.get("assigned_to") != null && !a.get("assigned_to").equals(userId);
        jdbc.update("""
            UPDATE assignment SET assigned_to = ?, previous_assignee = CASE WHEN ? THEN assigned_to ELSE previous_assignee END,
                reassigned_count = reassigned_count + CASE WHEN ? THEN 1 ELSE 0 END, last_assigned_at = CASE WHEN ? IS NULL THEN last_assigned_at ELSE now() END,
                status = CASE WHEN ? IS NULL AND status = 'ASSIGNED' THEN 'OPEN'::assignment_status
                              WHEN ? IS NOT NULL AND status = 'OPEN' THEN 'ASSIGNED'::assignment_status ELSE status END,
                priority = COALESCE(?, priority), updated_at = now()
            WHERE assignment_id = ?""", userId, reassign, reassign, userId, userId, userId, priority, assignmentId);
        return Map.of("assignmentId", assignmentId, "assignmentNumber", a.get("assignment_number"), "assignedTo", userId == null ? "" : userId, "reassigned", reassign);
    }
}
