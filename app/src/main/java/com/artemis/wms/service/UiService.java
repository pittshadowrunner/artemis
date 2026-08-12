package com.artemis.wms.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-model for the server-rendered UI. Everything is snapshot-on-load,
 * per the metrics design call — no auto-refresh. The UI is just another
 * API client conceptually; these queries mirror what the REST metrics
 * endpoints expose, plus a few page-specific rollups.
 */
@Service
public class UiService {

    private final JdbcTemplate jdbc;

    public UiService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Sites the current tenant can see, for the picker / default site. */
    /** Selection lane: assignment-level rows (a full selection assignment
     *  spans many items, so task columns don't apply). Zone filters via the
     *  wave's designation, falling back to the coldest task item. */
    public List<Map<String, Object>> selectionAssignments(UUID siteId, String zone) {
        String zoneFilter = zone == null || zone.isBlank() ? "" :
            " AND COALESCE(w.temp_zone::text, (SELECT CASE WHEN bool_or(i2.temp_zone IN ('FROZEN','DEEP_FROZEN')) THEN 'FROZEN' "
            + " WHEN bool_or(i2.temp_zone = 'REFRIGERATED') THEN 'REFRIGERATED' ELSE 'AMBIENT' END "
            + " FROM assignment_task t2 JOIN item i2 ON i2.item_id = t2.item_id WHERE t2.assignment_id = a.assignment_id)) = ? ";
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(siteId);
        if (!zoneFilter.isEmpty()) args.add(zone);
        return jdbc.queryForList("""
            SELECT a.assignment_id, a.assignment_number, a.priority,
                   w.wave_id, w.wave_number, tag_of(w.temp_zone::text) AS zone_tag,
                   css_of(w.temp_zone::text) AS zone_css,
                   e.equipment_id, e.code AS equipment_code,
                   a.assigned_to AS assigned_user_id, u.display_name AS operator,
                   count(t.task_id) AS tasks,
                   count(t.task_id) FILTER (WHERE t.status = 'COMPLETE') AS done,
                   CASE WHEN count(t.task_id) FILTER (WHERE t.status = 'COMPLETE') > 0 THEN 'IN PROGRESS'
                        WHEN a.assigned_to IS NOT NULL THEN 'ASSIGNED'
                        ELSE 'PENDING' END AS display_status
            FROM assignment a
            LEFT JOIN wave w ON w.wave_id = a.wave_id
            LEFT JOIN equipment e ON e.equipment_id = a.equipment_id
            LEFT JOIN app_user u ON u.user_id = a.assigned_to
            LEFT JOIN assignment_task t ON t.assignment_id = a.assignment_id
            WHERE a.site_id = ? AND a.assignment_type = 'SELECTION'
              AND a.status NOT IN ('COMPLETE','CANCELLED')
            """ + " " + zoneFilter + " " + """
            GROUP BY a.assignment_id, w.wave_id, e.equipment_id, u.display_name
            ORDER BY a.priority DESC, a.assignment_number
            """, args.toArray());
    }

    public List<Map<String, Object>> sites() {
        return jdbc.queryForList("""
            SELECT org_node_id, code, name FROM org_node
            WHERE level = 'SITE_LOCATION' AND active ORDER BY code
            """);
    }

    public Map<String, Object> breadcrumb(UUID siteId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            WITH RECURSIVE up AS (
                SELECT org_node_id, parent_id, level::text AS level, code, name
                FROM org_node WHERE org_node_id = ?
                UNION ALL
                SELECT o.org_node_id, o.parent_id, o.level::text, o.code, o.name
                FROM org_node o JOIN up ON o.org_node_id = up.parent_id
            ) SELECT level, code, name FROM up
            """, siteId);
        Map<String, Object> crumb = new java.util.HashMap<>();
        for (Map<String, Object> r : rows) crumb.put((String) r.get("level"), r);
        return crumb;
    }

    /** Hazard-stripe banner: the most active wave right now. */
    public Map<String, Object> activeWave(UUID siteId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT w.wave_id, w.wave_number, w.wave_type::text AS wave_type, w.status::text AS status,
                   vp.total_tasks, vp.done_tasks,
                   (SELECT count(*) FROM wave_order wo WHERE wo.wave_id = w.wave_id) AS orders
            FROM wave w JOIN v_wave_progress vp ON vp.wave_id = w.wave_id
            WHERE w.site_id = ? AND w.status IN ('RELEASED','PICKING')
            ORDER BY w.released_at DESC NULLS LAST LIMIT 1
            """, siteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Workstream lane counts across the top of the ops page. */
    public Map<String, Object> lanes(UUID siteId) {
        return jdbc.queryForMap("""
            SELECT
              (SELECT count(*) FROM receiving_manifest
                WHERE site_id = ? AND status IN ('ARRIVED','RECEIVING'))                       AS receiving,
              (SELECT count(*) FROM assignment_task t JOIN assignment a USING (assignment_id)
                WHERE a.site_id = ? AND a.assignment_type = 'PUTAWAY' AND t.status = 'OPEN')   AS putaway,
              (SELECT count(*) FROM v_replen_pressure WHERE site_id = ?)                       AS replen,
              (SELECT count(*) FROM assignment_task t JOIN assignment a USING (assignment_id)
                WHERE a.site_id = ? AND a.assignment_type = 'SELECTION' AND t.status = 'OPEN') AS selection,
              (SELECT count(*) FROM customer_order
                WHERE site_id = ? AND status = 'DROPPED')                                      AS shipping
            """, siteId, siteId, siteId, siteId, siteId);
    }

    /**
     * Open assignment tasks, sequenced by pick path — the heart of the ops
     * page. Placard tag/class are computed here so the template stays dumb.
     */
    public List<Map<String, Object>> openTasks(UUID siteId, int limit) {
        return openTasks(siteId, null, limit);
    }

    public List<Map<String, Object>> openTasks(UUID siteId, String type, int limit) {
        return openTasks(siteId, type, null, limit);
    }

    public List<Map<String, Object>> openTasks(UUID siteId, String type, String zone, int limit) {
        return jdbc.queryForList("""
            SELECT a.priority, a.assignment_id, a.assignment_number,
                   a.assignment_type::text AS assignment_type, t.status::text AS status,
                   lf.location_id AS from_id, lt.location_id AS to_id,
                   lf.code  AS from_code,
                   CASE lf.loc_type::text WHEN 'RECEIVING_DOCK' THEN 'DCK' WHEN 'DROP' THEN 'DRP'
                        WHEN 'STAGING' THEN 'STG' WHEN 'CROSS_DOCK' THEN 'XDK'
                        ELSE tag_of(lf.temp_zone::text) END AS from_tag,
                   css_of(lf.temp_zone::text) AS from_css,
                   CASE WHEN lt.location_id IS NOT NULL THEN lt.code
                        WHEN t.cart_position IS NOT NULL THEN 'POS ' || t.cart_position
                        ELSE NULL END AS to_code,
                   CASE WHEN lt.location_id IS NULL AND t.cart_position IS NOT NULL THEN 'CRT'
                        WHEN lt.location_id IS NULL THEN NULL
                        WHEN lt.loc_type::text = 'DROP' THEN 'DRP'
                        ELSE tag_of(lt.temp_zone::text) END AS to_tag,
                   COALESCE(css_of(lt.temp_zone::text), '') AS to_css,
                   COALESCE(it.description, '') AS item, i.inventory_id, i.lpn, i.lot_number, t.qty,
                   COALESCE(t.put_check_digits, t.check_digits) AS say_digits,
                   round(EXTRACT(EPOCH FROM (now() - a.created_at)) / 3600.0, 1) AS wait_hours
            FROM assignment_task t
            JOIN assignment a ON a.assignment_id = t.assignment_id
            LEFT JOIN location lf ON lf.location_id = t.from_location
            LEFT JOIN location lt ON lt.location_id = t.to_location
            LEFT JOIN inventory i ON i.inventory_id = t.inventory_id
            LEFT JOIN item it ON it.item_id = t.item_id
            WHERE a.site_id = ? AND t.status = 'OPEN'
            """ + " " + (type == null ? "" : " AND a.assignment_type = '" + switch (type) {
                case "PUTAWAY" -> "PUTAWAY";
                case "REPLENISHMENT" -> "REPLENISHMENT";
                case "SELECTION" -> "SELECTION";
                default -> throw new IllegalArgumentException("bad lane");
            } + "' ") + " " + (zone == null || zone.isBlank() ? "" : " AND it.temp_zone = '" + switch (zone) {
                case "DRY" -> "AMBIENT";
                case "CHL" -> "REFRIGERATED";
                case "FRZ" -> "FROZEN";
                default -> null;
            } + "' ") + " " + """
            ORDER BY a.priority DESC, lf.pick_sequence ASC NULLS LAST, t.seq
            LIMIT ?
            """, siteId, limit);
    }

    /** Receiving lane: open manifests with progress. */
    public List<Map<String, Object>> receivingOpen(UUID siteId) {
        return jdbc.queryForList("""
            SELECT manifest_id, manifest_number, carrier, status::text AS status,
                   expected_qty, received_qty, COALESCE(pct_complete, 0) AS pct
            FROM v_receiving_progress
            WHERE site_id = ? AND status IN ('ARRIVED','RECEIVING')
            ORDER BY manifest_number
            """, siteId);
    }

    /** Shipping lane: orders picked or dropped, awaiting load. */
    public List<Map<String, Object>> shippingAwaiting(UUID siteId) {
        return jdbc.queryForList("""
            SELECT o.order_id, o.order_number, o.status::text AS status,
                   c.code AS customer_code, c.name AS customer_name,
                   l.code AS drop_code,
                   count(ol.order_line_id) AS lines
            FROM customer_order o
            JOIN customer c ON c.customer_id = o.customer_id
            LEFT JOIN location l ON l.location_id = o.drop_location_id
            LEFT JOIN customer_order_line ol ON ol.order_id = o.order_id
            WHERE o.site_id = ? AND o.status IN ('PICKED','DROPPED')
            GROUP BY o.order_id, c.code, c.name, l.code
            ORDER BY o.order_number
            """, siteId);
    }

    /** Right rail: soonest-expiring lots with their slot. */
    public List<Map<String, Object>> expiringLots(UUID siteId, int limit) {
        return jdbc.queryForList("""
            SELECT it.description, inv.lot_number, l.code AS location_code,
                   (inv.expiration_date - CURRENT_DATE) AS days_remaining
            FROM inventory inv
            JOIN item it ON it.item_id = inv.item_id
            JOIN location l ON l.location_id = inv.location_id
            WHERE inv.site_id = ? AND inv.expiration_date IS NOT NULL
              AND inv.status IN ('AVAILABLE','ALLOCATED')
            ORDER BY inv.expiration_date LIMIT ?
            """, siteId, limit);
    }

    /** Right rail: occupied / total active slots per temp zone. */
    public List<Map<String, Object>> zoneOccupancy(UUID siteId) {
        return jdbc.queryForList("""
            SELECT l.temp_zone::text AS temp_zone, css_of(l.temp_zone::text) AS css,
                   count(*) AS total,
                   count(DISTINCT CASE WHEN i.inventory_id IS NOT NULL THEN l.location_id END) AS occupied,
                   round(100.0 * count(DISTINCT CASE WHEN i.inventory_id IS NOT NULL THEN l.location_id END)
                         / count(*), 0) AS pct
            FROM location l
            LEFT JOIN inventory i ON i.location_id = l.location_id
                 AND i.status IN ('AVAILABLE','ALLOCATED')
            WHERE l.site_id = ? AND l.active AND l.loc_type = 'STANDARD'
            GROUP BY l.temp_zone ORDER BY l.temp_zone
            """, siteId);
    }

    // ------------------------- metrics page -------------------------

    public Map<String, Object> kpis(UUID siteId) {
        return jdbc.queryForMap("""
            SELECT
              (SELECT COALESCE(count(*),0) FROM assignment_task t JOIN assignment a USING (assignment_id)
                WHERE a.site_id = ? AND a.assignment_type = 'SELECTION' AND t.status = 'COMPLETE'
                  AND t.completed_at >= CURRENT_DATE)                                          AS lines_picked,
              (SELECT COALESCE(sum(t.qty),0) FROM assignment_task t JOIN assignment a USING (assignment_id)
                WHERE a.site_id = ? AND t.status = 'COMPLETE'
                  AND t.completed_at >= CURRENT_DATE)                                          AS cases_moved,
              (SELECT count(*) FROM customer_order
                WHERE site_id = ? AND status NOT IN ('SHIPPED','CANCELLED'))                   AS orders_remaining,
              (SELECT count(*) FROM customer_order WHERE site_id = ?)                          AS orders_total,
              (SELECT COALESCE(round(100.0 * sum(received_qty) / NULLIF(sum(expected_qty),0), 0), 0)
                 FROM v_receiving_progress WHERE site_id = ?)                                  AS receipts_pct,
              (SELECT count(*) FROM v_replen_pressure WHERE site_id = ?)                       AS faces_below
            """, siteId, siteId, siteId, siteId, siteId, siteId);
    }

    public List<Map<String, Object>> velocity(UUID siteId, int limit) {
        return jdbc.queryForList("""
            SELECT v.location_code, css_of(l.temp_zone::text) AS css, tag_of(l.temp_zone::text) AS tag,
                   it.description, v.lines, v.visits, v.cases,
                   l.velocity_zone, it.velocity_class,
                   round(100.0 * v.lines / NULLIF(max(v.lines) OVER (), 0), 0) AS pct
            FROM v_pick_face_velocity v
            JOIN location l ON l.location_id = v.location_id
            LEFT JOIN item it ON it.item_id = v.item_id
            WHERE v.site_id = ? AND v.day >= CURRENT_DATE
            ORDER BY v.lines DESC LIMIT ?
            """, siteId, limit);
    }

    public List<Map<String, Object>> waveBoard(UUID siteId) {
        return jdbc.queryForList("""
            SELECT vp.wave_number, vp.wave_type::text AS wave_type, vp.status::text AS status,
                   vp.total_tasks, vp.done_tasks,
                   (SELECT count(*) FROM wave_order wo WHERE wo.wave_id = vp.wave_id) AS orders,
                   CASE WHEN vp.total_tasks > 0
                        THEN round(100.0 * vp.done_tasks / vp.total_tasks, 0) ELSE 0 END AS pct
            FROM v_wave_progress vp
            WHERE vp.site_id = ? ORDER BY vp.wave_number
            """, siteId);
    }

    public List<Map<String, Object>> receivingToday(UUID siteId) {
        return jdbc.queryForList("""
            SELECT manifest_id, manifest_number, carrier, status::text AS status,
                   expected_qty, received_qty, COALESCE(pct_complete, 0) AS pct
            FROM v_receiving_progress WHERE site_id = ? ORDER BY manifest_number
            """, siteId);
    }

    public List<Map<String, Object>> shippingPipeline(UUID siteId) {
        return jdbc.queryForList("""
            SELECT status, orders FROM (
                SELECT status::text AS status, count(*) AS orders,
                       CASE status::text WHEN 'SHIPPED' THEN 1 WHEN 'DROPPED' THEN 2 WHEN 'PICKED' THEN 3
                            WHEN 'PICKING' THEN 4 WHEN 'RELEASED' THEN 5 WHEN 'ALLOCATED' THEN 6 ELSE 7 END AS ord
                FROM customer_order WHERE site_id = ? GROUP BY status
            ) x ORDER BY ord
            """, siteId);
    }

    public List<Map<String, Object>> laborSelection(UUID siteId) {
        return jdbc.queryForList("""
            SELECT user_id, display_name, sum(cases) AS cases
            FROM v_labor_productivity
            WHERE site_id = ? AND assignment_type = 'SELECTION'
            GROUP BY user_id, display_name ORDER BY cases DESC LIMIT 8
            """, siteId);
    }
}
