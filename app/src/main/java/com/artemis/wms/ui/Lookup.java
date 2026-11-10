package com.artemis.wms.ui;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.platform.Values;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Read models for the entity pages: one page per thing you can point at on
 * the floor or in a document — slot, pallet, lot, item, customer, operator,
 * unit of equipment. Everything is addressed by the name printed on it (a
 * slot code, an LPN, a SKU, an order number) so any screen can link to it
 * without knowing an id. No business rules live here.
 */
@Service
public class Lookup {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    public Lookup(JdbcTemplate jdbc, AccessService access) { this.jdbc = jdbc; this.access = access; }

    private static Map<String, Object> one(List<Map<String, Object>> rows, String what) {
        if (rows.isEmpty()) throw ApiException.notFound(what + " not found.");
        return Values.deArray(new LinkedHashMap<>(rows.get(0)));
    }

    // ------------------------------------------------------------ resolvers
    /** Documents are linked by their printed number; ids still work. */
    public UUID resolve(String kind, String key, UUID preferSite) {
        UUID corp = access.requireTenant();
        try { return UUID.fromString(key); } catch (IllegalArgumentException notAnId) { /* a number, then */ }
        String sql = switch (kind) {
            case "order"      -> "SELECT order_id FROM customer_order WHERE corporation_id = ? AND upper(order_number) = upper(?) ORDER BY (site_id = ?) DESC LIMIT 1";
            case "wave"       -> "SELECT wave_id FROM wave WHERE corporation_id = ? AND upper(wave_number) = upper(?) ORDER BY (site_id = ?) DESC LIMIT 1";
            case "assignment" -> "SELECT assignment_id FROM assignment WHERE corporation_id = ? AND upper(assignment_number) = upper(?) ORDER BY (site_id = ?) DESC LIMIT 1";
            case "manifest"   -> "SELECT manifest_id FROM receiving_manifest WHERE corporation_id = ? AND upper(manifest_number) = upper(?) ORDER BY (site_id = ?) DESC LIMIT 1";
            case "shipment"   -> "SELECT shipment_id FROM shipment WHERE corporation_id = ? AND upper(shipment_number) = upper(?) ORDER BY (site_id = ?) DESC LIMIT 1";
            default -> throw ApiException.badRequest("Unknown document kind " + kind + ".");
        };
        List<UUID> ids = jdbc.queryForList(sql, UUID.class, corp, key, preferSite);
        if (ids.isEmpty()) throw ApiException.notFound(Character.toUpperCase(kind.charAt(0)) + kind.substring(1) + " " + key + " not found.");
        return ids.get(0);
    }

    // ------------------------------------------------------------ slot
    public Map<String, Object> slot(String code, UUID preferSite) {
        UUID corp = access.requireTenant();
        Map<String, Object> s = one(jdbc.queryForList("""
            SELECT l.location_id, l.site_id, st.code AS site, l.code, l.loc_type::text AS loc_type, l.temp_zone::text AS temp_zone,
                   l.zone, l.aisle, l.level, l.col, l.slot, l.pick_sequence, l.check_digits, l.rack_type::text AS rack_type,
                   l.velocity_zone, l.golden_zone, l.hazmat_approved, l.max_weight_kg, l.width_cm, l.depth_cm, l.height_cm, l.active,
                   ar.code AS area, i.sku::text AS face_sku, i.description AS face_description,
                   l.replen_min_qty, l.replen_trigger_qty, l.replen_max_qty
            FROM location l JOIN org_node st ON st.org_node_id = l.site_id
            LEFT JOIN org_node ar ON ar.org_node_id = l.area_id LEFT JOIN item i ON i.item_id = l.replen_item_id
            WHERE l.corporation_id = ? AND l.code = upper(?) ORDER BY (l.site_id = ?) DESC LIMIT 1""", corp, code, preferSite), "Location " + code);
        UUID id = (UUID) s.get("location_id");
        s.put("pallets", jdbc.queryForList("""
            SELECT v.lpn, i.sku::text AS sku, i.description, v.qty, v.original_qty, v.status::text AS status, v.lot_number, v.expiration_date, v.arrival_date,
                   v.qty - COALESCE((SELECT sum(a.qty - a.picked_qty) FROM allocation a WHERE a.inventory_id = v.inventory_id), 0) AS free_qty,
                   pt.to_code AS directed_to, pt.assignment_number
            FROM inventory v JOIN item i ON i.item_id = v.item_id LEFT JOIN v_pallet_task pt ON pt.inventory_id = v.inventory_id
            WHERE v.location_id = ? AND v.qty > 0 AND (v.status IN ('AVAILABLE','ALLOCATED','HOLD','DAMAGED') OR v.picked_from IS NOT NULL)
            ORDER BY v.expiration_date NULLS LAST, v.arrival_date, v.lpn""", id));
        s.put("face", s.get("face_sku") == null ? null : jdbc.queryForList(
            "SELECT on_hand, in_flight, open_picks FROM v_replen_pressure WHERE location_id = ?", id).stream().findFirst().orElse(null));
        // Work touching this slot: on its way in, on its way out.
        s.put("work", jdbc.queryForList("""
            SELECT a.assignment_id, a.assignment_number, a.assignment_type::text AS type, a.status::text AS status, a.priority, u.display_name AS operator, a.assigned_to,
                   CASE WHEN t.to_location = ? THEN 'IN' ELSE 'OUT' END AS direction,
                   inv.lpn, i.sku::text AS sku, t.qty, lf.code AS from_code, lt.code AS to_code
            FROM assignment_task t JOIN assignment a ON a.assignment_id = t.assignment_id
            LEFT JOIN app_user u ON u.user_id = a.assigned_to LEFT JOIN inventory inv ON inv.inventory_id = t.inventory_id LEFT JOIN item i ON i.item_id = t.item_id
            LEFT JOIN location lf ON lf.location_id = t.from_location LEFT JOIN location lt ON lt.location_id = t.to_location
            WHERE t.status = 'OPEN' AND a.status IN ('OPEN','ASSIGNED','IN_PROGRESS') AND (t.to_location = ? OR t.from_location = ?)
            ORDER BY a.priority DESC, a.created_at""", id, id, id));
        s.put("moves", jdbc.queryForList("""
            SELECT mv.created_at, mv.movement_type, inv.lpn, i.sku::text AS sku, mv.qty, lf.code AS from_code, lt.code AS to_code,
                   u.display_name AS performed_by, mv.performed_by AS user_id, a.assignment_number
            FROM inventory_movement mv JOIN inventory inv ON inv.inventory_id = mv.inventory_id JOIN item i ON i.item_id = inv.item_id
            LEFT JOIN location lf ON lf.location_id = mv.from_location LEFT JOIN location lt ON lt.location_id = mv.to_location
            LEFT JOIN app_user u ON u.user_id = mv.performed_by LEFT JOIN assignment a ON a.assignment_id = mv.assignment_id
            WHERE mv.from_location = ? OR mv.to_location = ? ORDER BY mv.created_at DESC LIMIT 30""", id, id));
        return s;
    }

    // ------------------------------------------------------------ pallet
    public List<Map<String, Object>> pallets(UUID siteId, String q, String status, String zone, String where) {
        return jdbc.queryForList("""
            SELECT v.lpn, i.sku::text AS sku, i.description, i.temp_zone::text AS temp_zone, l.code AS location, l.loc_type::text AS loc_type,
                   v.qty, v.original_qty, v.status::text AS status, v.lot_number, v.expiration_date, v.arrival_date, m.manifest_number,
                   pt.to_code AS directed_to, pt.assignment_number
            FROM inventory v JOIN item i ON i.item_id = v.item_id LEFT JOIN location l ON l.location_id = v.location_id
            LEFT JOIN receiving_manifest m ON m.manifest_id = v.manifest_id LEFT JOIN v_pallet_task pt ON pt.inventory_id = v.inventory_id
            WHERE v.site_id = ?
              AND (?::text IS NULL OR v.lpn ILIKE '%' || ? || '%' OR i.sku::text ILIKE '%' || ? || '%' OR v.lot_number ILIKE '%' || ? || '%' OR l.code ILIKE '%' || ? || '%')
              AND (?::text IS NULL OR v.status::text = ?)
              AND (?::text IS NULL OR i.temp_zone::text = ?)
              AND (?::text IS NULL OR (? = 'DOCK' AND l.loc_type IN ('RECEIVING_DOCK','CROSS_DOCK')) OR (? = 'FACE' AND l.replen_item_id IS NOT NULL)
                   OR (? = 'RESERVE' AND l.loc_type = 'STANDARD' AND l.replen_item_id IS NULL))
              AND (?::text IS NOT NULL OR v.status IN ('AVAILABLE','ALLOCATED','HOLD','DAMAGED'))
            ORDER BY v.created_at DESC, v.lpn LIMIT 500""",
            siteId, q, q, q, q, q, status, status, zone, zone, where, where, where, where, status);
    }

    public Map<String, Object> pallet(String lpn, UUID preferSite) {
        UUID corp = access.requireTenant();
        Map<String, Object> p = one(jdbc.queryForList("""
            SELECT v.inventory_id, v.lpn, v.site_id, s.code AS site, i.sku::text AS sku, i.description, i.temp_zone::text AS temp_zone, i.uom,
                   l.code AS location, l.loc_type::text AS loc_type, l.check_digits, v.qty, v.original_qty, v.status::text AS status,
                   v.qty - COALESCE((SELECT sum(a.qty - a.picked_qty) FROM allocation a WHERE a.inventory_id = v.inventory_id), 0) AS free_qty,
                   v.lot_number, v.expiration_date, v.expiration_date - CURRENT_DATE AS days_remaining, v.arrival_date, v.actual_weight_kg,
                   m.manifest_number, v.created_at, par.lpn AS parent_lpn,
                   eq.code AS equipment, v.cart_position, src.lpn AS picked_from, po.order_number AS for_order, pc.code AS for_customer, pc.name AS for_customer_name
            FROM inventory v JOIN item i ON i.item_id = v.item_id JOIN org_node s ON s.org_node_id = v.site_id
            LEFT JOIN location l ON l.location_id = v.location_id LEFT JOIN receiving_manifest m ON m.manifest_id = v.manifest_id
            LEFT JOIN inventory par ON par.inventory_id = v.parent_inventory_id
            LEFT JOIN equipment eq ON eq.equipment_id = v.equipment_id LEFT JOIN inventory src ON src.inventory_id = v.picked_from
            LEFT JOIN zone_order pz ON pz.zone_order_id = v.zone_order_id LEFT JOIN customer_order po ON po.order_id = pz.order_id
            LEFT JOIN customer pc ON pc.customer_id = po.customer_id
            WHERE v.corporation_id = ? AND v.lpn = upper(?) ORDER BY (v.site_id = ?) DESC LIMIT 1""", corp, lpn, preferSite), "Pallet " + lpn);
        UUID id = (UUID) p.get("inventory_id");
        p.put("serials", jdbc.queryForList("SELECT serial_number FROM inventory_serial WHERE inventory_id = ? ORDER BY 1", String.class, id));
        p.put("task", jdbc.queryForList("SELECT * , assignment_type::text AS type, assignment_status::text AS status FROM v_pallet_task WHERE inventory_id = ?", id).stream().findFirst().orElse(null));
        p.put("children", jdbc.queryForList("""
            SELECT c.lpn, c.qty, c.original_qty, c.status::text AS status, l.code AS location, c.created_at
            FROM inventory c LEFT JOIN location l ON l.location_id = c.location_id WHERE c.parent_inventory_id = ? ORDER BY c.created_at""", id));
        p.put("allocations", jdbc.queryForList("""
            SELECT o.order_number, c.code AS customer, c.name AS customer_name, a.qty, a.picked_qty, a.bypass, o.status::text AS order_status
            FROM allocation a JOIN customer_order_line ol ON ol.order_line_id = a.order_line_id JOIN customer_order o ON o.order_id = ol.order_id
            JOIN customer c ON c.customer_id = o.customer_id WHERE a.inventory_id = ? ORDER BY a.created_at""", id));
        // Where it has been, and for how long at each stop. The last stop is where it is now.
        p.put("stops", jdbc.queryForList("""
            SELECT lt.code AS location, lt.loc_type::text AS loc_type, mv.movement_type, mv.created_at AS arrived,
                   lead(mv.created_at) OVER (ORDER BY mv.created_at) AS departed,
                   COALESCE(lead(mv.created_at) OVER (ORDER BY mv.created_at), now()) - mv.created_at AS dwell,
                   u.display_name AS performed_by, mv.performed_by AS user_id, a.assignment_number
            FROM inventory_movement mv JOIN location lt ON lt.location_id = mv.to_location
            LEFT JOIN app_user u ON u.user_id = mv.performed_by LEFT JOIN assignment a ON a.assignment_id = mv.assignment_id
            WHERE mv.inventory_id = ? AND NOT (mv.movement_type = 'DROP' AND mv.qty = 0) ORDER BY mv.created_at""", id));
        // What has been picked out of this pallet, and where each of those picks is now.
        p.put("picks", jdbc.queryForList("""
            SELECT k.lpn, k.qty, k.status::text AS status, l.code AS location, eq.code AS equipment, k.cart_position, o.order_number, k.created_at
            FROM inventory k LEFT JOIN location l ON l.location_id = k.location_id LEFT JOIN equipment eq ON eq.equipment_id = k.equipment_id
            LEFT JOIN zone_order z ON z.zone_order_id = k.zone_order_id LEFT JOIN customer_order o ON o.order_id = z.order_id
            WHERE k.picked_from = ? ORDER BY k.created_at""", id));
        p.put("history", jdbc.queryForList("""
            SELECT mv.movement_type, lf.code AS from_location, lt.code AS to_location, mv.qty, u.display_name AS performed_by, mv.performed_by AS user_id,
                   a.assignment_number, mv.created_at
            FROM inventory_movement mv LEFT JOIN location lf ON lf.location_id = mv.from_location LEFT JOIN location lt ON lt.location_id = mv.to_location
            LEFT JOIN app_user u ON u.user_id = mv.performed_by LEFT JOIN assignment a ON a.assignment_id = mv.assignment_id
            WHERE mv.inventory_id = ? ORDER BY mv.created_at""", id));
        return p;
    }

    // ------------------------------------------------------------ lot
    public Map<String, Object> lot(String sku, String lot) {
        UUID corp = access.requireTenant();
        Map<String, Object> l = one(jdbc.queryForList("""
            SELECT v.sku::text AS sku, v.description, v.temp_zone::text AS temp_zone, v.lot_number, min(v.expiration_date) AS expiration_date,
                   min(v.expiration_date) - CURRENT_DATE AS days_remaining, min(v.first_arrival) AS first_arrival, max(v.last_arrival) AS last_arrival,
                   sum(v.pallets) AS pallets, sum(v.pallets_on_hand) AS pallets_on_hand, sum(v.qty_on_hand) AS qty_on_hand, sum(v.qty_received) AS qty_received,
                   (SELECT count(DISTINCT x.arrival_date) FROM inventory x JOIN item xi ON xi.item_id = x.item_id
                     WHERE x.corporation_id = ? AND xi.sku = ?::citext AND x.lot_number = ? AND x.parent_inventory_id IS NULL) AS arrival_dates
            FROM v_lot v WHERE v.corporation_id = ? AND v.sku = ?::citext AND v.lot_number = ?
            GROUP BY v.sku, v.description, v.temp_zone, v.lot_number""", corp, sku, lot, corp, sku, lot), "Lot " + lot + " of " + sku);
        l.put("pallets_list", jdbc.queryForList("""
            SELECT v.lpn, st.code AS site, loc.code AS location, loc.loc_type::text AS loc_type, v.qty, v.original_qty, v.status::text AS status,
                   v.arrival_date, v.expiration_date, m.manifest_number, par.lpn AS parent_lpn
            FROM inventory v JOIN item i ON i.item_id = v.item_id JOIN org_node st ON st.org_node_id = v.site_id
            LEFT JOIN location loc ON loc.location_id = v.location_id LEFT JOIN receiving_manifest m ON m.manifest_id = v.manifest_id
            LEFT JOIN inventory par ON par.inventory_id = v.parent_inventory_id
            WHERE v.corporation_id = ? AND i.sku = ?::citext AND v.lot_number = ? ORDER BY v.arrival_date, v.lpn""", corp, sku, lot));
        l.put("receipts", jdbc.queryForList("""
            SELECT m.manifest_number, m.carrier, m.status::text AS status, min(v.created_at) AS received_at, count(*) AS pallets, sum(v.original_qty) AS qty
            FROM inventory v JOIN item i ON i.item_id = v.item_id JOIN receiving_manifest m ON m.manifest_id = v.manifest_id
            WHERE v.corporation_id = ? AND i.sku = ?::citext AND v.lot_number = ? AND v.parent_inventory_id IS NULL
            GROUP BY m.manifest_id ORDER BY min(v.created_at)""", corp, sku, lot));
        // Recall trace: every order this lot was promised to or picked for.
        l.put("orders", jdbc.queryForList("""
            SELECT o.order_number, c.code AS customer, c.name AS customer_name, o.status::text AS order_status, v.lpn,
                   a.qty AS allocated_qty, a.picked_qty, s.shipment_number, s.shipped_at
            FROM allocation a JOIN inventory v ON v.inventory_id = a.inventory_id JOIN item i ON i.item_id = v.item_id
            JOIN customer_order_line ol ON ol.order_line_id = a.order_line_id JOIN customer_order o ON o.order_id = ol.order_id
            JOIN customer c ON c.customer_id = o.customer_id
            LEFT JOIN shipment_order so ON so.order_id = o.order_id LEFT JOIN shipment s ON s.shipment_id = so.shipment_id
            WHERE v.corporation_id = ? AND i.sku = ?::citext AND v.lot_number = ? ORDER BY o.order_number, v.lpn""", corp, sku, lot));
        return l;
    }

    public List<Map<String, Object>> lots(UUID siteId, String q) {
        return jdbc.queryForList("""
            SELECT sku::text AS sku, description, temp_zone::text AS temp_zone, lot_number, expiration_date, expiration_date - CURRENT_DATE AS days_remaining,
                   first_arrival, last_arrival, arrival_dates, pallets_on_hand, qty_on_hand, qty_received
            FROM v_lot WHERE site_id = ? AND (?::text IS NULL OR lot_number ILIKE '%' || ? || '%' OR sku::text ILIKE '%' || ? || '%')
            ORDER BY (qty_on_hand > 0) DESC, expiration_date NULLS LAST, sku, lot_number LIMIT 500""", siteId, q, q, q);
    }

    // ------------------------------------------------------------ item
    /** Everything the item page shows beyond the master record. */
    public Map<String, Object> itemContext(UUID itemId) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("velocity", jdbc.queryForList("SELECT velocity, lines_30d, cases_30d FROM v_item_velocity WHERE item_id = ?", itemId).stream().findFirst().orElse(null));
        c.put("stock", jdbc.queryForList("""
            SELECT st.code AS site, l.code AS location, l.loc_type::text AS loc_type, l.replen_item_id = v.item_id AS at_face, v.lpn, v.qty, v.status::text AS status,
                   v.lot_number, v.expiration_date, v.expiration_date - CURRENT_DATE AS days_remaining, v.arrival_date,
                   v.qty - COALESCE((SELECT sum(a.qty - a.picked_qty) FROM allocation a WHERE a.inventory_id = v.inventory_id), 0) AS free_qty
            FROM inventory v JOIN org_node st ON st.org_node_id = v.site_id LEFT JOIN location l ON l.location_id = v.location_id
            WHERE v.item_id = ? AND v.qty > 0 AND v.status IN ('AVAILABLE','ALLOCATED','HOLD','DAMAGED')
            ORDER BY st.code, (l.replen_item_id = v.item_id) DESC NULLS LAST, v.expiration_date NULLS LAST, v.arrival_date, v.lpn""", itemId));
        c.put("faces", jdbc.queryForList("""
            SELECT st.code AS site, p.location_code, p.on_hand, p.in_flight, p.replen_min_qty, p.replen_trigger_qty, p.replen_max_qty, p.open_picks
            FROM v_replen_pressure p JOIN org_node st ON st.org_node_id = p.site_id WHERE p.item_id = ? ORDER BY st.code, p.location_code""", itemId));
        c.put("lots", jdbc.queryForList("""
            SELECT lot_number, expiration_date, expiration_date - CURRENT_DATE AS days_remaining, pallets_on_hand, qty_on_hand, first_arrival
            FROM v_lot WHERE item_id = ? ORDER BY (qty_on_hand > 0) DESC, expiration_date NULLS LAST, lot_number LIMIT 50""", itemId));
        c.put("demand", jdbc.queryForList("""
            SELECT o.order_number, c.code AS customer, c.name AS customer_name, o.status::text AS status, o.requested_ship_date, ol.ordered_qty, ol.allocated_qty, ol.picked_qty
            FROM customer_order_line ol JOIN customer_order o ON o.order_id = ol.order_id JOIN customer c ON c.customer_id = o.customer_id
            WHERE ol.item_id = ? AND o.status NOT IN ('SHIPPED','CANCELLED') ORDER BY o.requested_ship_date NULLS LAST, o.order_number""", itemId));
        c.put("inbound", jdbc.queryForList("""
            SELECT m.manifest_number, m.carrier, m.status::text AS status, m.expected_date, ml.expected_qty, ml.received_qty
            FROM receiving_manifest_line ml JOIN receiving_manifest m ON m.manifest_id = ml.manifest_id
            WHERE ml.item_id = ? AND m.status NOT IN ('CLOSED','CANCELLED') ORDER BY m.expected_date NULLS LAST""", itemId));
        return c;
    }

    public List<Map<String, Object>> items(String q) {
        return jdbc.queryForList("""
            SELECT i.sku::text AS sku, i.description, i.temp_zone::text AS temp_zone, i.lot_tracked, i.expiry_tracked, i.serial_tracked, i.catch_weight,
                   iv.velocity, iv.lines_30d, b.sku::text AS base_item_sku,
                   COALESCE((SELECT sum(v.qty) FROM inventory v WHERE v.item_id = i.item_id AND v.status IN ('AVAILABLE','ALLOCATED')), 0) AS on_hand,
                   (SELECT count(*) FROM inventory v WHERE v.item_id = i.item_id AND v.qty > 0 AND v.status IN ('AVAILABLE','ALLOCATED')) AS pallets,
                   (SELECT string_agg(l.code, ', ' ORDER BY l.code) FROM location l WHERE l.replen_item_id = i.item_id AND l.active) AS faces
            FROM item i LEFT JOIN item b ON b.item_id = i.base_item_id LEFT JOIN v_item_velocity iv ON iv.item_id = i.item_id
            WHERE i.corporation_id = ? AND i.active AND (?::text IS NULL OR i.sku::text ILIKE '%' || ? || '%' OR i.description ILIKE '%' || ? || '%')
            ORDER BY i.sku""", access.requireTenant(), q, q, q);
    }

    // ------------------------------------------------------------ customer
    public Map<String, Object> customerContext(UUID customerId) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("orders", jdbc.queryForList("""
            SELECT p.order_number, st.code AS site, p.status::text AS status, p.rush, p.requested_ship_date, p.lines, p.ordered_qty, p.picked_qty, p.zones, p.created_at,
                   s.shipment_number, s.shipped_at
            FROM v_order_progress p JOIN customer_order o ON o.order_id = p.order_id JOIN org_node st ON st.org_node_id = p.site_id
            LEFT JOIN shipment_order so ON so.order_id = p.order_id LEFT JOIN shipment s ON s.shipment_id = so.shipment_id
            WHERE o.customer_id = ? ORDER BY p.created_at DESC LIMIT 200""", customerId));
        c.put("totals", jdbc.queryForMap("""
            SELECT count(*) AS orders, count(*) FILTER (WHERE status NOT IN ('SHIPPED','CANCELLED')) AS open_orders,
                   count(*) FILTER (WHERE status = 'SHIPPED') AS shipped_orders, max(created_at) AS last_order_at
            FROM customer_order WHERE customer_id = ?""", customerId));
        return c;
    }

    // ------------------------------------------------------------ operators
    public List<Map<String, Object>> operators(UUID corp) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT u.user_id, u.email::text AS email, u.display_name, u.trained_workflows, u.active, u.last_login_at,
                   (SELECT count(*) FROM assignment a WHERE a.assigned_to = u.user_id AND a.status IN ('ASSIGNED','IN_PROGRESS')) AS open_work,
                   COALESCE((SELECT sum(lp.tasks) FROM v_labor_productivity lp WHERE lp.user_id = u.user_id AND lp.day > now() - interval '7 days'), 0) AS tasks_7d,
                   COALESCE((SELECT sum(lp.cases) FROM v_labor_productivity lp WHERE lp.user_id = u.user_id AND lp.day > now() - interval '7 days'), 0) AS cases_7d,
                   (SELECT string_agg(e.code, ', ') FROM equipment_checkout co JOIN equipment e ON e.equipment_id = co.equipment_id
                     WHERE co.user_id = u.user_id AND co.checked_in_at IS NULL) AS equipment,
                   (SELECT string_agg(o.code || ' · ' || r.code, ', ' ORDER BY r.rank DESC) FROM user_org_grant g
                     JOIN org_node o ON o.org_node_id = g.org_node_id JOIN role r ON r.role_id = g.role_id WHERE g.user_id = u.user_id) AS grants
            FROM app_user u WHERE u.corporation_id = ? ORDER BY u.display_name""", corp);
        rows.forEach(Values::deArray);
        return rows;
    }

    public Map<String, Object> operator(UUID userId) {
        UUID corp = access.requireTenant();
        Map<String, Object> u = one(jdbc.queryForList("""
            SELECT u.user_id, u.email::text AS email, u.display_name, u.trained_workflows, u.active, u.last_login_at, u.created_at
            FROM app_user u WHERE u.user_id = ? AND u.corporation_id = ?""", userId, corp), "Operator");
        u.put("grants", jdbc.queryForList("""
            SELECT o.code AS node, o.level::text AS level, r.code AS role
            FROM user_org_grant g JOIN org_node o ON o.org_node_id = g.org_node_id JOIN role r ON r.role_id = g.role_id
            WHERE g.user_id = ? ORDER BY r.rank DESC""", userId));
        // Personal labor metrics: today, the last 7 days and the last 30, by workstream.
        u.put("labor", jdbc.queryForList("""
            SELECT assignment_type::text AS type,
                   COALESCE(sum(tasks) FILTER (WHERE day >= date_trunc('day', now())), 0) AS tasks_today,
                   COALESCE(sum(cases) FILTER (WHERE day >= date_trunc('day', now())), 0) AS cases_today,
                   COALESCE(sum(tasks) FILTER (WHERE day > now() - interval '7 days'), 0)  AS tasks_7d,
                   COALESCE(sum(cases) FILTER (WHERE day > now() - interval '7 days'), 0)  AS cases_7d,
                   sum(tasks) AS tasks_30d, sum(cases) AS cases_30d, count(DISTINCT day) AS days_worked
            FROM v_labor_productivity WHERE user_id = ? AND day > now() - interval '30 days'
            GROUP BY assignment_type ORDER BY sum(tasks) DESC""", userId));
        u.put("work", jdbc.queryForList("""
            SELECT a.assignment_id, a.assignment_number, a.assignment_type::text AS type, a.status::text AS status, a.priority, a.temp_zone::text AS temp_zone,
                   w.wave_number, e.code AS equipment, a.last_assigned_at, a.started_at, a.completed_at, a.completed_at - a.started_at AS took,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id) AS tasks,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id AND t.status = 'COMPLETE') AS done
            FROM assignment a LEFT JOIN wave w ON w.wave_id = a.wave_id LEFT JOIN equipment e ON e.equipment_id = a.equipment_id
            WHERE a.assigned_to = ? ORDER BY (a.status IN ('ASSIGNED','IN_PROGRESS')) DESC, COALESCE(a.completed_at, a.last_assigned_at, a.created_at) DESC LIMIT 40""", userId));
        u.put("uses", jdbc.queryForList("""
            SELECT e.code AS equipment, e.equipment_type::text AS type, co.checked_out_at, co.checked_in_at,
                   COALESCE(co.checked_in_at, now()) - co.checked_out_at AS held
            FROM equipment_checkout co JOIN equipment e ON e.equipment_id = co.equipment_id
            WHERE co.user_id = ? ORDER BY co.checked_out_at DESC LIMIT 25""", userId));
        return u;
    }

    // ------------------------------------------------------------ equipment
    public List<Map<String, Object>> fleet(UUID siteId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT e.equipment_id, e.code, e.equipment_type::text AS type, e.container_positions, e.capabilities, e.check_digits, e.max_weight_kg, e.active,
                   u.display_name AS operator, co.user_id, co.checked_out_at, now() - co.checked_out_at AS held,
                   (SELECT string_agg(a.assignment_number, ', ' ORDER BY a.assignment_number) FROM assignment a
                     WHERE a.equipment_id = e.equipment_id AND a.status IN ('OPEN','ASSIGNED','IN_PROGRESS')) AS open_work
            FROM equipment e
            LEFT JOIN equipment_checkout co ON co.equipment_id = e.equipment_id AND co.checked_in_at IS NULL
            LEFT JOIN app_user u ON u.user_id = co.user_id
            WHERE e.site_id = ? ORDER BY e.equipment_type, e.code""", siteId);
        rows.forEach(Values::deArray);
        return rows;
    }

    public Map<String, Object> equipment(String code, UUID preferSite) {
        UUID corp = access.requireTenant();
        Map<String, Object> e = one(jdbc.queryForList("""
            SELECT e.equipment_id, e.site_id, st.code AS site, e.code, e.equipment_type::text AS type, e.container_positions, e.capabilities, e.check_digits,
                   e.max_weight_kg, e.voice_enabled, e.active, e.lpn, u.display_name AS operator, co.user_id, co.checked_out_at, now() - co.checked_out_at AS held
            FROM equipment e JOIN org_node st ON st.org_node_id = e.site_id
            LEFT JOIN equipment_checkout co ON co.equipment_id = e.equipment_id AND co.checked_in_at IS NULL
            LEFT JOIN app_user u ON u.user_id = co.user_id
            WHERE e.corporation_id = ? AND e.code = upper(?) ORDER BY (e.site_id = ?) DESC LIMIT 1""", corp, code, preferSite), "Equipment " + code);
        UUID id = (UUID) e.get("equipment_id");
        // Each position, and what is riding in it right now: the container, the
        // zone order it belongs to (order + wave), and the confirmed picks.
        List<Map<String, Object>> positions = jdbc.queryForList("""
            WITH cur AS (   -- the assignment riding on this unit: in progress first, then picked-but-not-dropped, then next up
                SELECT a.assignment_id, a.assignment_number, a.status::text AS assignment_status
                FROM assignment a
                WHERE a.equipment_id = ? AND a.status IN ('OPEN','ASSIGNED','IN_PROGRESS','COMPLETE')
                  AND EXISTS (SELECT 1 FROM zone_order zz WHERE zz.assignment_id = a.assignment_id AND zz.dropped_at IS NULL)
                ORDER BY (a.status = 'IN_PROGRESS') DESC, (a.status = 'COMPLETE') DESC, a.priority DESC, a.created_at LIMIT 1)
            SELECT ep.position_no, ep.check_digits, cur.assignment_id, cur.assignment_number, cur.assignment_status,
                   o.order_number, c.code AS customer, c.name AS customer_name, w.wave_number, z.temp_zone::text AS temp_zone,
                   COALESCE(ac.inducted_lpn, ct.barcode) AS container, ct.container_type::text AS container_type
            FROM equipment_position ep
            LEFT JOIN cur ON true
            LEFT JOIN assignment_container ac ON ac.assignment_id = cur.assignment_id AND ac.cart_position = ep.position_no
            LEFT JOIN zone_order z ON z.zone_order_id = ac.zone_order_id
            LEFT JOIN customer_order o ON o.order_id = z.order_id LEFT JOIN customer c ON c.customer_id = o.customer_id
            LEFT JOIN wave w ON w.wave_id = z.wave_id LEFT JOIN container ct ON ct.container_id = ac.container_id
            WHERE ep.equipment_id = ? ORDER BY ep.position_no""", id, id);
        for (Map<String, Object> p : positions)
            p.put("contents", jdbc.queryForList("""
                SELECT v.lpn, i.sku::text AS sku, i.description, v.qty, v.lot_number, src.lpn AS from_lpn, o.order_number
                FROM inventory v JOIN item i ON i.item_id = v.item_id LEFT JOIN inventory src ON src.inventory_id = v.picked_from
                LEFT JOIN zone_order z ON z.zone_order_id = v.zone_order_id LEFT JOIN customer_order o ON o.order_id = z.order_id
                WHERE v.equipment_id = ? AND v.cart_position = ? AND v.location_id IS NULL AND v.qty > 0
                ORDER BY i.sku, v.created_at""", id, p.get("position_no")));
        e.put("positions", positions);
        e.put("work", jdbc.queryForList("""
            SELECT a.assignment_id, a.assignment_number, a.assignment_type::text AS type, a.status::text AS status, a.priority, w.wave_number, u.display_name AS operator, a.assigned_to,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id) AS tasks,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id AND t.status = 'COMPLETE') AS done
            FROM assignment a LEFT JOIN wave w ON w.wave_id = a.wave_id LEFT JOIN app_user u ON u.user_id = a.assigned_to
            WHERE a.equipment_id = ? AND a.status IN ('OPEN','ASSIGNED','IN_PROGRESS') ORDER BY a.priority DESC, a.created_at""", id));
        // Operator uses: who had it, out and in. Nothing about what it was used for.
        e.put("uses", jdbc.queryForList("""
            SELECT u.display_name AS operator, co.user_id, co.checked_out_at, co.checked_in_at, COALESCE(co.checked_in_at, now()) - co.checked_out_at AS held
            FROM equipment_checkout co JOIN app_user u ON u.user_id = co.user_id
            WHERE co.equipment_id = ? ORDER BY co.checked_out_at DESC LIMIT 30""", id));
        return e;
    }
}
