package com.artemis.wms.ui;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** Read models for screens that compose several views; no business rules live here. */
@Service
public class UiQueries {
    private final JdbcTemplate jdbc;
    public UiQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> site(UUID siteId) {
        return jdbc.queryForMap("SELECT s.org_node_id, s.code, s.name, s.city, s.state_province, s.timezone, r.code AS region FROM org_node s JOIN org_node r ON r.org_node_id = s.parent_id WHERE s.org_node_id = ?", siteId);
    }

    public Map<String, Object> boardCounts(UUID siteId) {
        return jdbc.queryForMap("""
            SELECT (SELECT count(*) FROM receiving_manifest WHERE site_id = ? AND status NOT IN ('CLOSED','CANCELLED')) AS manifests,
                   (SELECT count(*) FROM v_dock_dwell WHERE site_id = ?) AS on_dock,
                   (SELECT count(*) FROM assignment WHERE site_id = ? AND assignment_type = 'PUTAWAY' AND status IN ('OPEN','ASSIGNED','IN_PROGRESS')) AS putaway,
                   (SELECT count(*) FROM assignment WHERE site_id = ? AND assignment_type = 'SELECTION' AND status IN ('OPEN','ASSIGNED','IN_PROGRESS')) AS selection,
                   (SELECT count(*) FROM assignment WHERE site_id = ? AND assignment_type = 'REPLENISHMENT' AND status IN ('OPEN','ASSIGNED','IN_PROGRESS')) AS replen,
                   (SELECT count(*) FROM v_replen_pressure WHERE site_id = ? AND on_hand + in_flight <= replen_trigger_qty) AS faces_low,
                   (SELECT count(*) FROM customer_order WHERE site_id = ? AND status IN ('NEW','ALLOCATED','PICKING','PICKED')) AS open_orders,
                   (SELECT count(*) FROM customer_order WHERE site_id = ? AND status = 'DROPPED') AS ready_to_ship,
                   (SELECT count(*) FROM system_alert WHERE site_id = ? AND acknowledged_at IS NULL) AS alerts,
                   (SELECT count(*) FROM system_alert WHERE site_id = ? AND acknowledged_at IS NULL AND severity = 'CRITICAL') AS critical""",
            siteId, siteId, siteId, siteId, siteId, siteId, siteId, siteId, siteId, siteId);
    }

    /** Racking placards: zone → aisle → {levels desc, columns, cells[level|col-slot]}. */
    public Map<String, Map<String, Map<String, Object>>> racking(UUID siteId) {
        List<Map<String, Object>> locs = jdbc.queryForList("""
            SELECT l.code, l.zone, l.aisle, l.level, l.col, l.slot, l.check_digits, l.temp_zone::text AS temp_zone, css_of(l.temp_zone::text) AS css, tag_of(l.temp_zone::text) AS tag,
                   l.replen_item_id IS NOT NULL AS face, i.sku::text AS face_sku, l.replen_trigger_qty, l.replen_max_qty,
                   (SELECT count(*) FROM inventory v WHERE v.location_id = l.location_id AND v.status IN ('AVAILABLE','ALLOCATED')) AS pallets,
                   (SELECT COALESCE(sum(v.qty), 0) FROM inventory v WHERE v.location_id = l.location_id AND v.status IN ('AVAILABLE','ALLOCATED')) AS qty,
                   (SELECT string_agg(DISTINCT it.sku::text, ', ') FROM inventory v JOIN item it ON it.item_id = v.item_id WHERE v.location_id = l.location_id AND v.status IN ('AVAILABLE','ALLOCATED')) AS skus
            FROM location l LEFT JOIN item i ON i.item_id = l.replen_item_id
            WHERE l.site_id = ? AND l.loc_type = 'STANDARD' AND l.active AND l.aisle IS NOT NULL
            ORDER BY l.zone, l.aisle, fn_rank_token(l.col), l.slot, fn_rank_token(l.level) DESC""", siteId);
        Map<String, Map<String, Map<String, Object>>> zones = new LinkedHashMap<>();
        for (Map<String, Object> l : locs) {
            Map<String, Object> aisle = zones.computeIfAbsent((String) l.get("zone"), z -> new LinkedHashMap<>())
                .computeIfAbsent((String) l.get("aisle"), a -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("levels", new TreeSet<String>(Comparator.comparing((String x) -> x.matches("\\d+") ? Integer.parseInt(x) : 0).reversed())); m.put("columns", new LinkedHashSet<String>()); m.put("cells", new HashMap<String, Object>()); m.put("temp", l.get("temp_zone")); m.put("css", l.get("css")); m.put("tag", l.get("tag")); return m; });
            String cs = l.get("col") + "-" + l.get("slot");
            ((Set<String>) aisle.get("levels")).add((String) l.get("level"));
            ((Set<String>) aisle.get("columns")).add(cs);
            ((Map<String, Object>) aisle.get("cells")).put(l.get("level") + "|" + cs, l);
        }
        return zones;
    }

    public List<Map<String, Object>> docks(UUID siteId) {
        return jdbc.queryForList("""
            SELECT l.code, l.loc_type::text AS loc_type, l.check_digits,
                   (SELECT count(*) FROM inventory v WHERE v.location_id = l.location_id AND v.status IN ('AVAILABLE','ALLOCATED')) AS pallets,
                   (SELECT count(*) FROM zone_order z WHERE z.drop_location_id = l.location_id AND z.dropped_at > now() - interval '24 hours') AS drops_24h
            FROM location l WHERE l.site_id = ? AND l.loc_type <> 'STANDARD' AND l.active ORDER BY l.loc_type, l.code""", siteId);
    }

    public Map<String, Object> dashboard(UUID siteId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("receivingToday", jdbc.queryForList("SELECT * FROM v_receiving_progress WHERE site_id = ? AND (expected_date = CURRENT_DATE OR arrived_at::date = CURRENT_DATE OR status NOT IN ('CLOSED','CANCELLED')) ORDER BY status, manifest_number", siteId));
        d.put("shipping", jdbc.queryForList("SELECT * FROM v_shipping_progress WHERE site_id = ? AND (created_at::date = CURRENT_DATE OR status <> 'SHIPPED') ORDER BY status, shipment_number", siteId));
        d.put("velocity", jdbc.queryForList("SELECT * FROM v_pick_face_velocity WHERE site_id = ? ORDER BY lines_30d DESC, location_code", siteId));
        d.put("expiry", jdbc.queryForList("SELECT * FROM v_expiry_risk WHERE site_id = ? AND days_remaining <= 30 ORDER BY days_remaining, sku", siteId));
        d.put("labor", jdbc.queryForList("SELECT display_name, assignment_type::text AS assignment_type, sum(tasks) AS tasks, sum(cases) AS cases FROM v_labor_productivity WHERE site_id = ? AND day > now() - interval '7 days' GROUP BY display_name, assignment_type ORDER BY tasks DESC", siteId));
        d.put("dwell", jdbc.queryForList("SELECT * FROM v_dock_dwell WHERE site_id = ? ORDER BY dwell DESC", siteId));
        d.put("orders", jdbc.queryForList("SELECT status::text AS status, count(*) AS n FROM customer_order WHERE site_id = ? GROUP BY status ORDER BY 1", siteId));
        return d;
    }

    public List<Map<String, Object>> openAssignments(UUID siteId, String type) {
        return jdbc.queryForList("""
            SELECT a.assignment_id, a.assignment_number, a.assignment_type::text AS type, a.status::text AS status, a.priority, a.temp_zone::text AS temp_zone, tag_of(a.temp_zone::text) AS tag, css_of(a.temp_zone::text) AS css,
                   u.display_name AS assigned_to, w.wave_number, m.manifest_number, e.code AS equipment,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id) AS tasks,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id AND t.status = 'COMPLETE') AS done,
                   (SELECT string_agg(DISTINCT lt.code, ', ') FROM assignment_task t JOIN location lt ON lt.location_id = t.to_location WHERE t.assignment_id = a.assignment_id) AS destinations,
                   (SELECT string_agg(DISTINCT inv.lpn, ', ') FROM assignment_task t JOIN inventory inv ON inv.inventory_id = t.inventory_id WHERE t.assignment_id = a.assignment_id) AS lpns,
                   now() - a.created_at AS age
            FROM assignment a LEFT JOIN app_user u ON u.user_id = a.assigned_to LEFT JOIN wave w ON w.wave_id = a.wave_id
            LEFT JOIN receiving_manifest m ON m.manifest_id = a.manifest_id LEFT JOIN equipment e ON e.equipment_id = a.equipment_id
            WHERE a.site_id = ? AND a.assignment_type::text = ? AND a.status IN ('OPEN','ASSIGNED','IN_PROGRESS')
            ORDER BY a.priority DESC, a.created_at""", siteId, type);
    }

    public List<Map<String, Object>> operators(UUID siteId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT DISTINCT u.user_id, u.display_name, u.trained_workflows,
                   (SELECT count(*) FROM assignment a WHERE a.assigned_to = u.user_id AND a.status IN ('ASSIGNED','IN_PROGRESS')) AS open_work
            FROM app_user u JOIN user_org_grant g ON g.user_id = u.user_id
            WHERE u.active AND g.org_node_id IN (SELECT org_node_id FROM org_ancestry(?)) ORDER BY u.display_name""", siteId);
        rows.forEach(com.artemis.wms.platform.Values::deArray);
        return rows;
    }

    public List<Map<String, Object>> orgTree(UUID corp) {
        return jdbc.queryForList("""
            WITH RECURSIVE t AS (
                SELECT o.*, 0 AS depth, o.code::text AS path FROM org_node o WHERE o.org_node_id = ?
                UNION ALL
                SELECT o.*, t.depth + 1, t.path || '/' || o.code FROM org_node o JOIN t ON o.parent_id = t.org_node_id)
            SELECT org_node_id, parent_id, level::text AS level, code, name, city, state_province, depth, path, active,
                   (SELECT rotation::text FROM inventory_policy p WHERE p.org_node_id = t.org_node_id) AS declared_rotation,
                   (effective_policy(org_node_id)).rotation::text AS eff_rotation,
                   (effective_policy(org_node_id)).min_shelf_life_receipt_days AS eff_receipt_days,
                   (effective_policy(org_node_id)).min_shelf_life_ship_days AS eff_ship_days
            FROM t ORDER BY path""", corp);
    }
}
