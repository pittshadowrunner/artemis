package com.artemis.wms.web;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.service.LocationService.str;

/**
 * Labor dispatch. Assigning work to a person is a supervisor action —
 * gated on DASHBOARD_VIEW, the same tier that reads labor productivity.
 * This is what populates assignment.assigned_to, which in turn feeds
 * v_labor_productivity — no more SQL workaround.
 */
@RestController
@RequestMapping("/api/v1/assignments")
public class AssignmentController {

    private final JdbcTemplate jdbc;
    private final CapabilityService caps;

    public AssignmentController(JdbcTemplate jdbc, CapabilityService caps) {
        this.jdbc = jdbc; this.caps = caps;
    }

    @PostMapping("/{id}/assign")
    public Map<String, Object> assign(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.DASHBOARD_VIEW);
        String email = str(body.get("userEmail"));
        UUID userId = body.get("userId") == null ? null : UUID.fromString(body.get("userId").toString());
        if (userId == null) {
            if (email == null) throw ApiException.badRequest("Supply userEmail or userId.");
            List<UUID> ids = jdbc.queryForList(
                "SELECT user_id FROM app_user WHERE email = ?::citext AND active", UUID.class, email);
            if (ids.isEmpty()) throw ApiException.notFound("No active user with that email.");
            userId = ids.get(0);
        }
        int n = jdbc.update("""
            UPDATE assignment SET
                previous_assignee = CASE WHEN assigned_to IS NOT NULL AND assigned_to <> ? THEN assigned_to
                                         ELSE previous_assignee END,
                reassigned_count  = reassigned_count + CASE WHEN assigned_to IS NOT NULL AND assigned_to <> ? THEN 1 ELSE 0 END,
                assigned_to = ?, last_assigned_at = now(),
                status = CASE WHEN status = 'OPEN' THEN 'ASSIGNED' ELSE status END,
                started_at = COALESCE(started_at, now())
            WHERE assignment_id = ? AND status NOT IN ('COMPLETE','CANCELLED')
            """, userId, userId, userId, id);
        if (n == 0) throw ApiException.conflict("Assignment not found or already complete.");
        return Map.of("assignmentId", id, "assignedTo", userId);
    }

    /** Live status for polling UIs: assignment display state + per-task states. */
    @GetMapping("/{id}/status")
    public Map<String, Object> status(@PathVariable UUID id) {
        caps.require(TenantContext.user(), null, Capabilities.DASHBOARD_VIEW);
        Map<String, Object> a = jdbc.queryForMap("""
            SELECT a.status::text AS status, a.reassigned_count, u.display_name AS assigned_to,
                   pu.display_name AS previous_assignee,
                   count(t.task_id) AS total,
                   count(t.task_id) FILTER (WHERE t.status = 'COMPLETE') AS done,
                   CASE WHEN a.status = 'CANCELLED' THEN 'CANCELLED'
                        WHEN a.status = 'COMPLETE' THEN 'COMPLETE'
                        WHEN count(t.task_id) FILTER (WHERE t.status = 'COMPLETE') > 0 THEN 'IN PROGRESS'
                        WHEN a.reassigned_count > 0 THEN 'REASSIGNED'
                        WHEN a.assigned_to IS NOT NULL THEN 'ASSIGNED'
                        ELSE 'PENDING' END AS display_status
            FROM assignment a
            LEFT JOIN app_user u ON u.user_id = a.assigned_to
            LEFT JOIN app_user pu ON pu.user_id = a.previous_assignee
            LEFT JOIN assignment_task t ON t.assignment_id = a.assignment_id
            WHERE a.assignment_id = ?
            GROUP BY a.assignment_id, u.display_name, pu.display_name
            """, id);
        a.put("tasks", jdbc.queryForList(
            "SELECT task_id, status::text AS status FROM assignment_task WHERE assignment_id = ?", id));
        return a;
    }

    /** Attach a tote/container to a cart position — completes the put address: cart + position + tote. */
    @PostMapping("/{id}/containers")
    public Map<String, Object> attachContainer(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.SELECTION_EXECUTE);
        int position = Integer.parseInt(body.get("cartPosition").toString());
        String barcode = str(body.get("barcode"));
        List<UUID> cid = jdbc.queryForList("""
            SELECT c.container_id FROM container c
            JOIN assignment a ON a.assignment_id = ? AND a.site_id = c.site_id
            WHERE c.barcode = ? AND c.active
            """, UUID.class, id, barcode);
        if (cid.isEmpty()) throw ApiException.notFound("No active container with that barcode at this site.");
        int n = jdbc.update("""
            UPDATE assignment_container SET container_id = ?
            WHERE assignment_id = ? AND cart_position = ?
            """, cid.get(0), id, position);
        if (n == 0) throw ApiException.conflict("No such cart position on this assignment.");
        return Map.of("assignmentId", id, "cartPosition", position, "container", barcode);
    }

    @GetMapping
    public List<Map<String, Object>> open(@RequestParam UUID siteId,
                                          @RequestParam(required = false) String type) {
        caps.require(TenantContext.user(), siteId, Capabilities.DASHBOARD_VIEW);
        String filter = type == null ? "" : " AND a.assignment_type = ?::text::assignment_type ";
        Object[] args = type == null ? new Object[]{siteId} : new Object[]{siteId, type};
        return jdbc.queryForList("""
            SELECT a.assignment_id, a.assignment_type::text AS assignment_type, a.status::text AS status,
                   a.priority, u.display_name AS assigned_to, a.created_at,
                   count(t.task_id) AS tasks,
                   count(t.task_id) FILTER (WHERE t.status = 'COMPLETE') AS done
            FROM assignment a
            LEFT JOIN app_user u ON u.user_id = a.assigned_to
            LEFT JOIN assignment_task t ON t.assignment_id = a.assignment_id
            WHERE a.site_id = ? AND a.status NOT IN ('COMPLETE','CANCELLED')
            """ + filter + """
            GROUP BY a.assignment_id, u.display_name
            ORDER BY a.priority DESC, a.created_at
            """, args);
    }
}
