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

    /** A lane shows its most urgent work; the header carries the true count. */
    public static final int LANE = 60;
    private static final String HOLDS = "(SELECT true AS has FROM inventory v WHERE v.location_id = l.location_id AND v.qty > 0 AND v.status IN ('AVAILABLE','ALLOCATED') LIMIT 1)";

    /** The site's rack zones — one per temperature — with how full they are. One grouped pass. */
    public List<Map<String, Object>> locationZones(UUID siteId) {
        return jdbc.queryForList("""
            SELECT COALESCE(l.zone, '—') AS zone, min(l.temp_zone)::text AS temp_zone, min(a.name) AS area_name,
                   count(DISTINCT l.aisle) AS aisles, count(*) AS slots,
                   count(*) FILTER (WHERE l.replen_item_id IS NOT NULL) AS faces, count(o.has) AS occupied,
                   count(*) FILTER (WHERE l.replen_item_id IS NOT NULL AND o.has IS NULL) AS empty_faces
            FROM location l LEFT JOIN org_node a ON a.org_node_id = l.area_id LEFT JOIN LATERAL\s""" + HOLDS + """
             o ON true
            WHERE l.site_id = ? AND l.loc_type = 'STANDARD' AND l.active
            GROUP BY l.zone ORDER BY min(l.temp_zone) DESC, l.zone""", siteId);
    }

    /** The aisles of one zone. */
    public List<Map<String, Object>> locationAisles(UUID siteId, String zone) {
        return jdbc.queryForList("""
            SELECT l.aisle, count(*) AS slots, count(*) FILTER (WHERE l.replen_item_id IS NOT NULL) AS faces,
                   count(o.has) AS occupied, count(*) FILTER (WHERE l.replen_item_id IS NOT NULL AND o.has IS NULL) AS empty_faces
            FROM location l LEFT JOIN LATERAL\s""" + HOLDS + """
             o ON true
            WHERE l.site_id = ? AND l.loc_type = 'STANDARD' AND l.active AND l.aisle IS NOT NULL AND COALESCE(l.zone, '—') = ?
            GROUP BY l.aisle ORDER BY fn_rank_token(l.aisle), l.aisle""", siteId, zone);
    }

    /** Racking placards for one zone, or one aisle of it: zone → aisle → {levels desc, columns, cells[level|col-slot]}. */
    public Map<String, Map<String, Map<String, Object>>> racking(UUID siteId, String zone, String onlyAisle) {
        List<Map<String, Object>> locs = jdbc.queryForList("""
            SELECT l.code, l.zone, l.aisle, l.level, l.col, l.slot, l.check_digits, l.temp_zone::text AS temp_zone, css_of(l.temp_zone::text) AS css, tag_of(l.temp_zone::text) AS tag,
                   l.replen_item_id IS NOT NULL AS face, i.sku::text AS face_sku, l.replen_trigger_qty, l.replen_max_qty,
                   (SELECT count(*) FROM inventory v WHERE v.location_id = l.location_id AND v.qty > 0 AND (v.status IN ('AVAILABLE','ALLOCATED') OR v.picked_from IS NOT NULL)) AS pallets,
                   (SELECT COALESCE(sum(v.qty), 0) FROM inventory v WHERE v.location_id = l.location_id AND v.status IN ('AVAILABLE','ALLOCATED')) AS qty,
                   (SELECT min(it.sku::text) FROM inventory v JOIN item it ON it.item_id = v.item_id WHERE v.location_id = l.location_id AND v.qty > 0 AND v.status IN ('AVAILABLE','ALLOCATED')) AS sku
            FROM location l LEFT JOIN item i ON i.item_id = l.replen_item_id
            WHERE l.site_id = ? AND l.loc_type = 'STANDARD' AND l.active AND l.aisle IS NOT NULL
              AND COALESCE(l.zone, '—') = ? AND (?::text IS NULL OR l.aisle = ?)
            ORDER BY l.zone, fn_rank_token(l.aisle), l.aisle, fn_rank_token(l.col), l.slot, fn_rank_token(l.level) DESC""", siteId, zone, onlyAisle, onlyAisle);
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
            SELECT l.code, l.loc_type::text AS loc_type, l.check_digits, location_flow(l.loc_type, l.flow) AS flow, ar.code AS area, l.temp_zone::text AS temp_zone,
                   (SELECT count(*) FROM inventory v WHERE v.location_id = l.location_id AND v.qty > 0 AND (v.status IN ('AVAILABLE','ALLOCATED') OR v.picked_from IS NOT NULL)) AS pallets,
                   (SELECT COALESCE(sum(v.qty), 0) FROM inventory v WHERE v.location_id = l.location_id AND v.qty > 0 AND (v.status IN ('AVAILABLE','ALLOCATED') OR v.picked_from IS NOT NULL)) AS qty,
                   (SELECT count(*) FROM assignment_task t JOIN assignment a ON a.assignment_id = t.assignment_id WHERE t.from_location = l.location_id AND t.status = 'OPEN' AND a.status IN ('OPEN','ASSIGNED','IN_PROGRESS')) AS open_tasks,
                   (SELECT count(*) FROM zone_order z WHERE z.drop_location_id = l.location_id AND z.dropped_at > now() - interval '24 hours') AS drops_24h
            FROM location l LEFT JOIN org_node ar ON ar.org_node_id = l.area_id
            WHERE l.site_id = ? AND l.loc_type <> 'STANDARD' AND l.active ORDER BY l.loc_type, l.code""", siteId);
    }

    public Map<String, Object> dashboard(UUID siteId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("receivingToday", jdbc.queryForList("SELECT * FROM v_receiving_progress WHERE site_id = ? AND (expected_date = CURRENT_DATE OR arrived_at::date = CURRENT_DATE OR status NOT IN ('CLOSED','CANCELLED')) ORDER BY status, manifest_number", siteId));
        d.put("shipping", jdbc.queryForList("SELECT * FROM v_shipping_progress WHERE site_id = ? AND (created_at::date = CURRENT_DATE OR status <> 'SHIPPED') ORDER BY status, shipment_number", siteId));
        d.put("velocity", jdbc.queryForList("SELECT *, sku::text AS sku FROM v_pick_face_velocity WHERE site_id = ? AND lines_30d > 0 ORDER BY lines_30d DESC, location_code LIMIT 25", siteId));
        d.put("expiry", jdbc.queryForList("SELECT r.*, r.sku::text AS sku FROM v_expiry_risk r WHERE r.site_id = ? AND r.days_remaining <= 30 ORDER BY r.days_remaining, r.sku LIMIT 50", siteId));
        d.put("expiryTotal", jdbc.queryForObject("SELECT count(*) FROM v_expiry_risk WHERE site_id = ? AND days_remaining <= 30", Integer.class, siteId));
        d.put("labor", jdbc.queryForList("SELECT user_id, display_name, assignment_type::text AS assignment_type, sum(tasks) AS tasks, sum(cases) AS cases FROM v_labor_productivity WHERE site_id = ? AND day > now() - interval '7 days' GROUP BY user_id, display_name, assignment_type ORDER BY tasks DESC", siteId));
        d.put("dwell", jdbc.queryForList("SELECT *, temp_zone::text AS temp_zone, sku::text AS sku FROM v_dock_dwell WHERE site_id = ? ORDER BY dwell DESC", siteId));
        d.put("orders", jdbc.queryForList("SELECT status::text AS status, count(*) AS n FROM customer_order WHERE site_id = ? GROUP BY status ORDER BY 1", siteId));
        return d;
    }

    /**
     * Open work of one type for a board lane, optionally one temp zone.
     * Putaway and replenishment are one pallet each, so the card carries the
     * pallet, where it is and where it's going; selection is assignment-level
     * (many items across a pick path) and carries its wave, unit and orders.
     */
    public List<Map<String, Object>> openAssignments(UUID siteId, String type, String zone) {
        return jdbc.queryForList("""
            SELECT count(*) OVER () AS total, a.assignment_id, a.assignment_number, a.assignment_type::text AS type, a.status::text AS status, a.priority, a.temp_zone::text AS temp_zone,
                   u.display_name AS assigned_to, a.assigned_to AS assigned_user_id, w.wave_number, m.manifest_number, e.code AS equipment,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id) AS tasks,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id AND t.status = 'COMPLETE') AS done,
                   (SELECT count(*) FROM assignment_container ac WHERE ac.assignment_id = a.assignment_id) AS orders,
                   first.lpn, first.sku, first.qty, first.from_code, first.to_code,
                   now() - COALESCE(first.landed_at, a.created_at) AS waiting,
                   EXTRACT(EPOCH FROM now() - COALESCE(first.landed_at, a.created_at)) > 4 * 3600 AS overdue
            FROM assignment a LEFT JOIN app_user u ON u.user_id = a.assigned_to LEFT JOIN wave w ON w.wave_id = a.wave_id
            LEFT JOIN receiving_manifest m ON m.manifest_id = a.manifest_id LEFT JOIN equipment e ON e.equipment_id = a.equipment_id
            LEFT JOIN LATERAL (
                SELECT inv.lpn, i.sku::text AS sku, t.qty, lf.code AS from_code, lt.code AS to_code,
                       CASE WHEN a.assignment_type = 'PUTAWAY' THEN inv.created_at END AS landed_at
                FROM assignment_task t LEFT JOIN inventory inv ON inv.inventory_id = t.inventory_id LEFT JOIN item i ON i.item_id = t.item_id
                LEFT JOIN location lf ON lf.location_id = t.from_location LEFT JOIN location lt ON lt.location_id = t.to_location
                WHERE t.assignment_id = a.assignment_id ORDER BY t.seq LIMIT 1) first ON true
            WHERE a.site_id = ? AND a.assignment_type::text = ? AND a.status IN ('OPEN','ASSIGNED','IN_PROGRESS')
              AND (?::text IS NULL OR a.temp_zone::text = ?)
            ORDER BY a.priority DESC, a.created_at LIMIT\s""" + LANE + "", siteId, type, zone, zone);
    }

    /** The order master list: filter by status, customer (code or name), and ship-date window. */
    public List<Map<String, Object>> orders(UUID siteId, String status, String customer, java.time.LocalDate from, java.time.LocalDate to) {
        return jdbc.queryForList("""
            SELECT p.*, p.status::text AS status FROM v_order_progress p
            WHERE p.site_id = ? AND (?::text IS NULL OR p.status::text = ?)
              AND (?::text IS NULL OR p.customer_code ILIKE '%' || ? || '%' OR p.customer_name ILIKE '%' || ? || '%' OR p.order_number ILIKE '%' || ? || '%')
              AND (?::date IS NULL OR COALESCE(p.requested_ship_date, p.created_at::date) >= ?::date)
              AND (?::date IS NULL OR COALESCE(p.requested_ship_date, p.created_at::date) <= ?::date)
            ORDER BY p.rush DESC, p.priority DESC, p.requested_ship_date NULLS LAST, p.order_number LIMIT 500""",
            siteId, status, status, customer, customer, customer, customer, from, from, to, to);
    }

    /** Zone orders nobody has waved yet — what the wave planner picks from. */
    public List<Map<String, Object>> unwavedZoneOrders(UUID siteId) {
        return jdbc.queryForList("""
            SELECT z.temp_zone::text AS temp_zone, o.order_number, c.code AS customer, c.name AS customer_name, o.requested_ship_date, o.rush, o.priority,
                   o.route_code, o.stop_sequence,
                   (SELECT count(*) FROM customer_order_line ol JOIN item i ON i.item_id = ol.item_id WHERE ol.order_id = o.order_id AND i.temp_zone = z.temp_zone) AS lines,
                   (SELECT COALESCE(sum(ol.ordered_qty), 0) FROM customer_order_line ol JOIN item i ON i.item_id = ol.item_id WHERE ol.order_id = o.order_id AND i.temp_zone = z.temp_zone) AS qty
            FROM zone_order z JOIN customer_order o ON o.order_id = z.order_id JOIN customer c ON c.customer_id = o.customer_id
            WHERE o.site_id = ? AND z.wave_id IS NULL AND o.status NOT IN ('SHIPPED','CANCELLED')
            ORDER BY z.temp_zone, o.rush DESC, o.requested_ship_date NULLS LAST, o.order_number""", siteId);
    }

    /** Shipping lane: orders down at the dock and not yet on a truck, then open shipments. */
    public List<Map<String, Object>> readyToShip(UUID siteId) {
        return jdbc.queryForList("""
            SELECT p.order_number, p.customer_code, p.customer_name, p.requested_ship_date, p.route_code, p.stop_sequence, p.rush, p.zones,
                   (SELECT string_agg(DISTINCT l.code, ', ') FROM zone_order z JOIN location l ON l.location_id = z.drop_location_id WHERE z.order_id = p.order_id) AS dropped_at,
                   s.shipment_number, s.status::text AS shipment_status
            FROM v_order_progress p LEFT JOIN shipment_order so ON so.order_id = p.order_id LEFT JOIN shipment s ON s.shipment_id = so.shipment_id
            WHERE p.site_id = ? AND p.status = 'DROPPED' ORDER BY p.rush DESC, p.requested_ship_date NULLS LAST, p.order_number""", siteId);
    }

    /** What's physically sitting on each open manifest's dock, for the receiving lane. */
    public List<Map<String, Object>> openManifests(UUID siteId) {
        return jdbc.queryForList("""
            SELECT p.manifest_id, p.manifest_number, p.carrier, p.status::text AS status, p.expected_date, p.arrived_at, p.expected_qty, p.received_qty, p.pct_complete,
                   (SELECT count(*) FROM inventory v JOIN location l ON l.location_id = v.location_id
                     WHERE v.manifest_id = p.manifest_id AND l.loc_type IN ('RECEIVING_DOCK','CROSS_DOCK') AND v.status = 'AVAILABLE') AS on_dock,
                   (SELECT count(*) FROM inventory v WHERE v.manifest_id = p.manifest_id AND v.parent_inventory_id IS NULL) AS pallets
            FROM v_receiving_progress p WHERE p.site_id = ? AND p.status NOT IN ('CLOSED','CANCELLED')
            ORDER BY (p.status = 'EXPECTED'), p.arrived_at NULLS LAST, p.manifest_number""", siteId);
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
