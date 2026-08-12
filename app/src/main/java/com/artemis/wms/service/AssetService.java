package com.artemis.wms.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-model for the asset screens. Every asset renders the same way —
 * header, attribute grid, children table — and every child row links one
 * level down while the breadcrumb links back up. Check digits shown here
 * are the AUTHORITATIVE ones on the asset (slot / cart position / tote);
 * assignment tasks carry snapshots taken at creation for the voice prompt.
 */
@Service
public class AssetService {

    private final JdbcTemplate jdbc;

    public AssetService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> hubCounts(UUID siteId) {
        return jdbc.queryForMap("""
            SELECT
              (SELECT count(*) FROM item i WHERE EXISTS
                 (SELECT 1 FROM org_node s WHERE s.org_node_id = ? AND s.corporation_id = i.corporation_id)) AS items,
              (SELECT count(*) FROM location WHERE site_id = ? AND active)                                   AS slots,
              (SELECT count(*) FROM org_node WHERE parent_id = ? AND level = 'AREA')                         AS zones,
              (SELECT count(*) FROM equipment WHERE site_id = ? AND active)                                  AS equipment,
              (SELECT count(*) FROM container WHERE site_id = ? AND active)                                  AS containers,
              (SELECT count(*) FROM inventory WHERE site_id = ? AND lpn IS NOT NULL)                         AS pallets,
              (SELECT count(*) FROM (SELECT 1 FROM inventory WHERE site_id = ?
                 AND lot_number IS NOT NULL GROUP BY item_id, lot_number) x)                                 AS lots,
              (SELECT count(*) FROM assignment WHERE site_id = ?
                 AND status NOT IN ('COMPLETE','CANCELLED'))                                                 AS open_assignments
            """, siteId, siteId, siteId, siteId, siteId, siteId, siteId, siteId);
    }

    // ----------------------------- items -----------------------------

    public List<Map<String, Object>> items(UUID siteId) {
        return jdbc.queryForList("""
            SELECT i.item_id, i.sku::text AS sku, i.description, i.uom,
                   i.temp_zone::text AS temp_zone, css_of(i.temp_zone::text) AS css,
                   tag_of(i.temp_zone::text) AS tag,
                   i.velocity_class, v.calc_velocity, v.lines AS lines_30d,
                   i.lot_tracked, i.expiry_tracked, i.catch_weight
            FROM item i
            JOIN org_node s ON s.org_node_id = ? AND s.corporation_id = i.corporation_id
            LEFT JOIN v_item_velocity v ON v.item_id = i.item_id AND v.site_id = ?
            ORDER BY COALESCE(v.lines, 0) DESC, i.sku
            """, siteId, siteId);
    }

    public Map<String, Object> item(UUID itemId, UUID siteId) {
        Map<String, Object> it = jdbc.queryForMap("""
            SELECT i.item_id, i.sku::text AS sku, i.description, i.uom, i.weight_kg,
                   i.temp_zone::text AS temp_zone, css_of(i.temp_zone::text) AS css,
                   i.lot_tracked, i.expiry_tracked, i.serial_tracked, i.shelf_life_days,
                   i.date_label::text AS date_label, i.catch_weight, i.nominal_weight_kg,
                   i.min_shelf_life_receipt_days, i.min_shelf_life_ship_days,
                   i.allergens, i.certifications, i.country_of_origin,
                   i.gtin_each, i.gtin_case, i.inner_pack_qty, i.case_pack_qty,
                   i.pallet_ti, i.pallet_hi, i.hazmat_class,
                   i.velocity_class,
                   v.calc_velocity, v.lines AS lines_30d, v.cases AS cases_30d, v.last_pick_at
            FROM item i
            LEFT JOIN v_item_velocity v ON v.item_id = i.item_id AND v.site_id = ?
            WHERE i.item_id = ?
            """, siteId, itemId);
        it.put("uoms", jdbc.queryForList("""
            WITH RECURSIVE chain AS (
                SELECT u.code, u.qty, u.of_code, u.qty::numeric AS eaches, 1 AS depth
                FROM item_uom u WHERE u.item_id = ? AND u.of_code = 'EA'
                UNION ALL
                SELECT u.code, u.qty, u.of_code, u.qty * c.eaches, c.depth + 1
                FROM item_uom u JOIN chain c ON c.code = u.of_code AND u.item_id = ?
            )
            SELECT code, qty, of_code, eaches, depth FROM chain ORDER BY eaches
            """, itemId, itemId));
        it.put("stock", jdbc.queryForList("""
            SELECT l.location_id, l.code AS location_code, l.loc_type::text AS loc_type,
                   css_of(l.temp_zone::text) AS css, tag_of(l.temp_zone::text) AS tag,
                   inv.lpn, inv.lot_number, inv.expiration_date, inv.qty, inv.status::text AS status
            FROM inventory inv JOIN location l ON l.location_id = inv.location_id
            WHERE inv.item_id = ? AND inv.site_id = ? AND inv.status IN ('AVAILABLE','ALLOCATED')
            ORDER BY l.pick_sequence NULLS LAST
            """, itemId, siteId));
        it.put("faces", jdbc.queryForList("""
            SELECT location_id, code, replen_min_qty, replen_max_qty, replen_trigger_qty
            FROM location WHERE replen_item_id = ? AND site_id = ?
            """, itemId, siteId));
        return it;
    }

    // ----------------------------- slots & zones -----------------------------

    public List<Map<String, Object>> slots(UUID siteId, UUID areaId) {
        String filter = areaId == null ? "" : " AND l.area_id = ? ";
        Object[] args = areaId == null ? new Object[]{siteId} : new Object[]{siteId, areaId};
        return jdbc.queryForList("""
            SELECT l.location_id, l.code, l.loc_type::text AS loc_type, l.pick_sequence,
                   l.check_digits, l.temp_zone::text AS temp_zone,
                   css_of(l.temp_zone::text) AS css, tag_of(l.temp_zone::text) AS tag,
                   l.rack_type::text AS rack_type, l.velocity_zone, l.golden_zone,
                   a.code AS zone_code, a.org_node_id AS zone_id,
                   COALESCE(sum(inv.qty), 0) AS on_hand,
                   COALESCE(ri.item_id, si.item_id) AS item_id,
                   COALESCE(ri.sku::text, si.sku) AS item_sku
            FROM location l
            LEFT JOIN org_node a ON a.org_node_id = l.area_id
            LEFT JOIN inventory inv ON inv.location_id = l.location_id
                 AND inv.status IN ('AVAILABLE','ALLOCATED')
            LEFT JOIN item ri ON ri.item_id = l.replen_item_id
            LEFT JOIN LATERAL (
                SELECT i2.item_id, i2.sku::text AS sku
                FROM inventory v2 JOIN item i2 ON i2.item_id = v2.item_id
                WHERE v2.location_id = l.location_id
                  AND v2.status IN ('AVAILABLE','ALLOCATED')
                LIMIT 1) si ON true
            WHERE l.site_id = ? AND l.active """ + " " + filter + " " + """
            GROUP BY l.location_id, a.code, a.org_node_id, ri.item_id, ri.sku, si.item_id, si.sku
            ORDER BY l.pick_sequence NULLS LAST, l.code
            """, args);
    }

    public Map<String, Object> slot(UUID locationId) {
        Map<String, Object> s = jdbc.queryForMap("""
            SELECT l.location_id, l.site_id, l.code, l.loc_type::text AS loc_type,
                   l.check_digits, l.pick_sequence,
                   l.aisle, l.bay, l.tier, l.slot,
                   l.temp_zone::text AS temp_zone, css_of(l.temp_zone::text) AS css,
                   tag_of(l.temp_zone::text) AS tag,
                   l.rack_type::text AS rack_type, l.velocity_zone, l.golden_zone,
                   l.equipment_class, l.hazmat_approved, l.humidity_controlled,
                   l.width_cm, l.depth_cm, l.height_cm, l.max_weight_kg,
                   a.code AS zone_code, a.name AS zone_name, a.org_node_id AS zone_id,
                   ri.sku::text AS replen_sku, ri.item_id AS replen_item_id,
                   l.replen_min_qty, l.replen_max_qty, l.replen_trigger_qty
            FROM location l
            LEFT JOIN org_node a ON a.org_node_id = l.area_id
            LEFT JOIN item ri ON ri.item_id = l.replen_item_id
            WHERE l.location_id = ?
            """, locationId);
        s.put("inventory", jdbc.queryForList("""
            SELECT inv.lpn, i.item_id, i.sku::text AS sku, i.description, inv.lot_number,
                   inv.expiration_date, inv.qty, inv.status::text AS status
            FROM inventory inv JOIN item i ON i.item_id = inv.item_id
            WHERE inv.location_id = ? AND inv.status IN ('AVAILABLE','ALLOCATED')
            ORDER BY inv.expiration_date NULLS LAST
            """, locationId));
        return s;
    }

    public List<Map<String, Object>> zones(UUID siteId) {
        return jdbc.queryForList("""
            SELECT a.org_node_id AS zone_id, a.code, a.name,
                   count(l.location_id) AS slots,
                   count(l.location_id) FILTER (WHERE l.replen_item_id IS NOT NULL) AS pick_faces,
                   min(l.temp_zone::text) AS temp_zone, css_of(min(l.temp_zone::text)) AS css,
                   tag_of(min(l.temp_zone::text)) AS tag
            FROM org_node a
            LEFT JOIN location l ON l.area_id = a.org_node_id AND l.active
            WHERE a.parent_id = ? AND a.level = 'AREA'
            GROUP BY a.org_node_id ORDER BY a.code
            """, siteId);
    }

    public Map<String, Object> zone(UUID zoneId) {
        return jdbc.queryForMap("""
            SELECT org_node_id AS zone_id, parent_id AS site_id, code, name
            FROM org_node WHERE org_node_id = ?
            """, zoneId);
    }

    // ----------------------------- equipment & containers -----------------------------

    public List<Map<String, Object>> equipmentList(UUID siteId) {
        return jdbc.queryForList("""
            SELECT equipment_id, code, equipment_type::text AS equipment_type, lpn,
                   check_digits, container_positions, capabilities, max_weight_kg,
                   voice_enabled, active
            FROM equipment WHERE site_id = ? ORDER BY code
            """, siteId);
    }

    public Map<String, Object> equipment(UUID equipmentId) {
        Map<String, Object> e = jdbc.queryForMap("""
            SELECT equipment_id, site_id, code, equipment_type::text AS equipment_type,
                   lpn, check_digits, container_positions, capabilities, max_weight_kg,
                   voice_enabled, active
            FROM equipment WHERE equipment_id = ?
            """, equipmentId);
        e.put("positions", jdbc.queryForList("""
            SELECT ep.position_no, ep.check_digits,
                   c.container_id, c.barcode, c.container_type::text AS container_type,
                   o.order_id, o.order_number, w.wave_id, w.wave_number,
                   COALESCE(sum(t.qty) FILTER (WHERE t.status = 'COMPLETE'), 0) AS confirmed_qty
            FROM equipment_position ep
            LEFT JOIN assignment a ON a.equipment_id = ep.equipment_id
                 AND a.status NOT IN ('COMPLETE','CANCELLED')
            LEFT JOIN assignment_container ac ON ac.assignment_id = a.assignment_id
                 AND ac.cart_position = ep.position_no
            LEFT JOIN container c ON c.container_id = ac.container_id
            LEFT JOIN customer_order o ON o.order_id = ac.order_id
            LEFT JOIN wave w ON w.wave_id = a.wave_id
            LEFT JOIN assignment_task t ON t.assignment_id = a.assignment_id
                 AND t.cart_position = ep.position_no
            WHERE ep.equipment_id = ?
            GROUP BY ep.position_no, ep.check_digits, c.container_id, o.order_id, w.wave_id
            ORDER BY ep.position_no
            """, equipmentId));
        // Operator uses: who had this unit and when. Assignment/wave detail
        // intentionally omitted — the checkout log is about custody, not work.
        e.put("uses", jdbc.queryForList("""
            SELECT ec.checkout_id, u.user_id, u.display_name,
                   ec.checked_out_at, ec.checked_in_at,
                   round(EXTRACT(EPOCH FROM (COALESCE(ec.checked_in_at, now()) - ec.checked_out_at)) / 3600.0, 1) AS hours
            FROM equipment_checkout ec JOIN app_user u ON u.user_id = ec.user_id
            WHERE ec.equipment_id = ?
            ORDER BY ec.checked_out_at DESC LIMIT 20
            """, equipmentId));
        e.put("openCheckout", jdbc.queryForList("""
            SELECT ec.checkout_id, u.user_id, u.display_name, ec.checked_out_at
            FROM equipment_checkout ec JOIN app_user u ON u.user_id = ec.user_id
            WHERE ec.equipment_id = ? AND ec.checked_in_at IS NULL
            """, equipmentId));
        return e;
    }

    public List<Map<String, Object>> containers(UUID siteId) {
        return jdbc.queryForList("""
            SELECT container_id, barcode, container_type::text AS container_type,
                   check_digits, reusable, tare_weight_kg, max_weight_kg, active
            FROM container WHERE site_id = ? ORDER BY barcode
            """, siteId);
    }

    public Map<String, Object> container(UUID containerId) {
        Map<String, Object> c = jdbc.queryForMap("""
            SELECT container_id, site_id, barcode, container_type::text AS container_type,
                   check_digits, reusable, tare_weight_kg, max_weight_kg, active
            FROM container WHERE container_id = ?
            """, containerId);
        // Confirmed contents: what has actually been picked into this
        // container, linked to the order and wave it serves.
        c.put("contents", jdbc.queryForList("""
            SELECT i.item_id, i.sku::text AS sku, i.description,
                   sum(t.qty) AS qty, ac.order_id, o.order_number,
                   w.wave_id, w.wave_number, ac.cart_position,
                   a.assignment_id, a.assignment_number
            FROM assignment_container ac
            JOIN assignment a ON a.assignment_id = ac.assignment_id
            JOIN customer_order o ON o.order_id = ac.order_id
            LEFT JOIN wave w ON w.wave_id = a.wave_id
            JOIN assignment_task t ON t.assignment_id = ac.assignment_id
                 AND t.cart_position = ac.cart_position AND t.status = 'COMPLETE'
            JOIN item i ON i.item_id = t.item_id
            WHERE ac.container_id = ?
            GROUP BY i.item_id, ac.order_id, o.order_number, w.wave_id, ac.cart_position, a.assignment_id
            ORDER BY a.created_at DESC, i.sku
            """, containerId));
        return c;
    }

    public List<Map<String, Object>> freeContainers(UUID siteId) {
        return jdbc.queryForList("""
            SELECT c.barcode, c.check_digits FROM container c
            WHERE c.site_id = ? AND c.active AND NOT EXISTS (
                SELECT 1 FROM assignment_container ac
                JOIN assignment a ON a.assignment_id = ac.assignment_id
                WHERE ac.container_id = c.container_id
                  AND a.status NOT IN ('COMPLETE','CANCELLED'))
            ORDER BY c.barcode
            """, siteId);
    }

    public Map<String, Object> manifest(UUID manifestId) {
        Map<String, Object> m = jdbc.queryForMap("""
            SELECT m.manifest_id, m.site_id, m.manifest_number, m.carrier, m.trailer_number,
                   m.status::text AS status, m.expected_date, m.arrived_at, m.closed_at,
                   sum(ml.expected_qty) AS expected_qty, sum(ml.received_qty) AS received_qty
            FROM receiving_manifest m
            LEFT JOIN receiving_manifest_line ml ON ml.manifest_id = m.manifest_id
            WHERE m.manifest_id = ?
            GROUP BY m.manifest_id
            """, manifestId);
        m.put("lines", jdbc.queryForList("""
            SELECT ml.line_number, i.item_id, i.sku::text AS sku, i.description,
                   ml.expected_qty, ml.received_qty,
                   round(100.0 * ml.received_qty / NULLIF(ml.expected_qty, 0), 0) AS pct
            FROM receiving_manifest_line ml JOIN item i ON i.item_id = ml.item_id
            WHERE ml.manifest_id = ? ORDER BY ml.line_number
            """, manifestId));
        m.put("lpns", jdbc.queryForList("""
            SELECT inv.lpn, i.item_id, i.sku::text AS sku, inv.lot_number, inv.expiration_date,
                   inv.qty, inv.status::text AS status, inv.created_at AS received_at,
                   l.location_id, l.code AS location_code, l.loc_type::text AS loc_type,
                   tag_of(l.temp_zone::text) AS tag, css_of(l.temp_zone::text) AS css
            FROM inventory inv
            JOIN item i ON i.item_id = inv.item_id
            LEFT JOIN location l ON l.location_id = inv.location_id
            WHERE inv.received_from_manifest = ?
            ORDER BY inv.lpn
            """, manifestId));
        return m;
    }

    /** Pallet lookup: LPN prefix/exact match with full captured attributes. */
    public List<Map<String, Object>> lpnSearch(UUID siteId, String q) {
        return jdbc.queryForList("""
            SELECT inv.inventory_id, inv.lpn, inv.status::text AS status, inv.qty,
                   inv.lot_number, inv.expiration_date, inv.arrival_date, inv.created_at,
                   i.item_id, i.sku::text AS sku, i.description,
                   l.location_id, l.code AS location_code, l.loc_type::text AS loc_type,
                   tag_of(l.temp_zone::text) AS tag, css_of(l.temp_zone::text) AS css,
                   m.manifest_id, m.manifest_number
            FROM inventory inv
            JOIN item i ON i.item_id = inv.item_id
            LEFT JOIN location l ON l.location_id = inv.location_id
            LEFT JOIN receiving_manifest m ON m.manifest_id = inv.received_from_manifest
            WHERE inv.site_id = ? AND inv.lpn ILIKE ? || '%'
            ORDER BY inv.lpn LIMIT 25
            """, siteId, q);
    }

    // ----------------------------- pallets & lots -----------------------------

    /** Pallets: LPN-identified inventory. Search by LPN prefix. */
    public List<Map<String, Object>> pallets(UUID siteId, String q) {
        return jdbc.queryForList("""
            SELECT inv.inventory_id, inv.lpn, inv.status::text AS status,
                   inv.qty, inv.original_qty, inv.lot_number, inv.expiration_date,
                   i.item_id, i.sku::text AS sku, i.description,
                   l.location_id, l.code AS location_code, l.loc_type::text AS loc_type,
                   tag_of(l.temp_zone::text) AS tag, css_of(l.temp_zone::text) AS css,
                   m.manifest_id, m.manifest_number
            FROM inventory inv
            JOIN item i ON i.item_id = inv.item_id
            LEFT JOIN location l ON l.location_id = inv.location_id
            LEFT JOIN receiving_manifest m ON m.manifest_id = inv.received_from_manifest
            WHERE inv.site_id = ? AND (? = '' OR inv.lpn ILIKE ? || '%')
            ORDER BY inv.created_at DESC, inv.lpn LIMIT 60
            """, siteId, q, q);
    }

    /** One pallet: lineage, attributes, and everywhere it has been. */
    public Map<String, Object> pallet(UUID inventoryId) {
        Map<String, Object> pal = jdbc.queryForMap("""
            SELECT inv.inventory_id, inv.site_id, inv.lpn, inv.status::text AS status,
                   inv.qty, inv.original_qty, inv.lot_number, inv.expiration_date,
                   inv.arrival_date, inv.actual_weight_kg, inv.created_at,
                   i.item_id, i.sku::text AS sku, i.description,
                   tag_of(i.temp_zone::text) AS item_tag, css_of(i.temp_zone::text) AS item_css,
                   l.location_id, l.code AS location_code, l.loc_type::text AS loc_type,
                   tag_of(l.temp_zone::text) AS tag, css_of(l.temp_zone::text) AS css,
                   m.manifest_id, m.manifest_number
            FROM inventory inv
            JOIN item i ON i.item_id = inv.item_id
            LEFT JOIN location l ON l.location_id = inv.location_id
            LEFT JOIN receiving_manifest m ON m.manifest_id = inv.received_from_manifest
            WHERE inv.inventory_id = ?
            """, inventoryId);
        // Location history: movements stitched into stays with dwell per stop.
        pal.put("history", jdbc.queryForList("""
            WITH stops AS (
                SELECT mv.created_at AS arrived,
                       lead(mv.created_at) OVER (ORDER BY mv.created_at) AS departed,
                       lt.code AS location_code, lt.loc_type::text AS loc_type,
                       tag_of(lt.temp_zone::text) AS tag, css_of(lt.temp_zone::text) AS css,
                       mv.movement_type::text AS how, u.display_name AS by_whom, u.user_id
                FROM (
                    SELECT created_at, to_location, movement_type, performed_by FROM inventory_movement
                    WHERE inventory_id = ?
                    UNION ALL
                    SELECT inv.created_at, inv.location_id, 'RECEIVE', NULL
                    FROM inventory inv WHERE inv.inventory_id = ?
                      AND inv.received_from_manifest IS NOT NULL
                ) mv
                LEFT JOIN location lt ON lt.location_id = mv.to_location
                LEFT JOIN app_user u ON u.user_id = mv.performed_by
            )
            SELECT *, round(EXTRACT(EPOCH FROM (COALESCE(departed, now()) - arrived)) / 3600.0, 1) AS hours
            FROM stops ORDER BY arrived
            """, inventoryId, inventoryId));
        return pal;
    }

    /** Lots: grouped view — one lot can span pallets (and, in the wild,
     *  receipts). Keyed by (item, lot) which is the practical identity. */
    public List<Map<String, Object>> lots(UUID siteId, String q) {
        return jdbc.queryForList("""
            SELECT inv.lot_number, i.item_id, i.sku::text AS sku, i.description,
                   tag_of(i.temp_zone::text) AS tag, css_of(i.temp_zone::text) AS css,
                   min(inv.expiration_date) AS first_expiry,
                   count(*) AS pallets, sum(inv.qty) AS qty,
                   min(inv.arrival_date) AS first_arrival, max(inv.arrival_date) AS last_arrival
            FROM inventory inv JOIN item i ON i.item_id = inv.item_id
            WHERE inv.site_id = ? AND inv.lot_number IS NOT NULL
              AND (? = '' OR inv.lot_number ILIKE ? || '%')
            GROUP BY inv.lot_number, i.item_id
            ORDER BY min(inv.expiration_date) NULLS LAST, inv.lot_number LIMIT 60
            """, siteId, q, q);
    }

    public Map<String, Object> lot(UUID siteId, UUID itemId, String lotNumber) {
        Map<String, Object> lot = jdbc.queryForMap("""
            SELECT i.item_id, i.sku::text AS sku, i.description, ? AS lot_number,
                   tag_of(i.temp_zone::text) AS tag, css_of(i.temp_zone::text) AS css,
                   min(inv.expiration_date) AS expiration_date,
                   count(*) AS pallet_count, sum(inv.qty) AS total_qty,
                   sum(inv.original_qty) AS original_qty,
                   min(inv.arrival_date) AS first_arrival, max(inv.arrival_date) AS last_arrival
            FROM inventory inv JOIN item i ON i.item_id = inv.item_id
            WHERE inv.site_id = ? AND inv.item_id = ? AND inv.lot_number = ?
            GROUP BY i.item_id
            """, lotNumber, siteId, itemId, lotNumber);
        lot.put("pallets", jdbc.queryForList("""
            SELECT inv.inventory_id, inv.lpn, inv.status::text AS status,
                   inv.qty, inv.original_qty, inv.expiration_date, inv.arrival_date,
                   l.code AS location_code, l.loc_type::text AS loc_type,
                   tag_of(l.temp_zone::text) AS tag, css_of(l.temp_zone::text) AS css,
                   m.manifest_id, m.manifest_number
            FROM inventory inv
            LEFT JOIN location l ON l.location_id = inv.location_id
            LEFT JOIN receiving_manifest m ON m.manifest_id = inv.received_from_manifest
            WHERE inv.site_id = ? AND inv.item_id = ? AND inv.lot_number = ?
            ORDER BY inv.lpn
            """, siteId, itemId, lotNumber));
        return lot;
    }

    // ----------------------------- waves & assignments -----------------------------

    public List<Map<String, Object>> waves(UUID siteId) {
        return jdbc.queryForList("""
            SELECT w.wave_id, w.wave_number, w.wave_type::text AS wave_type,
                   w.status::text AS status, w.created_at, w.released_at,
                   (SELECT count(*) FROM wave_order wo WHERE wo.wave_id = w.wave_id) AS orders,
                   count(DISTINCT a.assignment_id) AS assignments,
                   count(t.task_id) AS tasks,
                   count(t.task_id) FILTER (WHERE t.status = 'COMPLETE') AS done
            FROM wave w
            LEFT JOIN assignment a ON a.wave_id = w.wave_id
            LEFT JOIN assignment_task t ON t.assignment_id = a.assignment_id
            WHERE w.site_id = ?
            GROUP BY w.wave_id ORDER BY w.created_at DESC
            """, siteId);
    }

    public Map<String, Object> wave(UUID waveId) {
        Map<String, Object> w = jdbc.queryForMap("""
            SELECT w.wave_id, w.site_id, w.wave_number, w.wave_type::text AS wave_type,
                   w.status::text AS status, w.carrier_cutoff, w.route_code,
                   w.created_at, w.released_at, w.completed_at,
                   u.display_name AS planned_by
            FROM wave w LEFT JOIN app_user u ON u.user_id = w.planned_by
            WHERE w.wave_id = ?
            """, waveId);
        w.put("assignments", jdbc.queryForList("""
            SELECT a.assignment_id, a.assignment_number, a.status::text AS status,
                   CASE WHEN a.status = 'CANCELLED' THEN 'CANCELLED'
                        WHEN a.status = 'COMPLETE' THEN 'COMPLETE'
                        WHEN count(t.task_id) FILTER (WHERE t.status = 'COMPLETE') > 0 THEN 'IN PROGRESS'
                        WHEN a.reassigned_count > 0 THEN 'REASSIGNED'
                        WHEN a.assigned_to IS NOT NULL THEN 'ASSIGNED'
                        ELSE 'PENDING' END AS display_status,
                   e.code AS equipment_code, e.equipment_id,
                   u.display_name AS assigned_to,
                   count(t.task_id) AS tasks,
                   count(t.task_id) FILTER (WHERE t.status = 'COMPLETE') AS done,
                   count(DISTINCT ac.order_id) AS orders
            FROM assignment a
            LEFT JOIN equipment e ON e.equipment_id = a.equipment_id
            LEFT JOIN app_user u ON u.user_id = a.assigned_to
            LEFT JOIN assignment_task t ON t.assignment_id = a.assignment_id
            LEFT JOIN assignment_container ac ON ac.assignment_id = a.assignment_id
            WHERE a.wave_id = ?
            GROUP BY a.assignment_id, e.code, e.equipment_id, u.display_name
            ORDER BY a.assignment_number
            """, waveId));
        w.put("orders", jdbc.queryForList("""
            SELECT o.order_id, o.order_number, o.status::text AS status,
                   c.code AS customer_code, c.name AS customer_name,
                   EXISTS (SELECT 1 FROM assignment_container ac
                           JOIN assignment a2 ON a2.assignment_id = ac.assignment_id
                           WHERE ac.order_id = o.order_id
                             AND a2.status <> 'CANCELLED') AS on_assignment
            FROM wave_order wo
            JOIN customer_order o ON o.order_id = wo.order_id
            JOIN customer c ON c.customer_id = o.customer_id
            WHERE wo.wave_id = ? ORDER BY o.order_number
            """, waveId));
        return w;
    }

    public Map<String, Object> assignment(UUID assignmentId) {
        Map<String, Object> a = jdbc.queryForMap("""
            SELECT a.assignment_id, a.site_id, a.assignment_number,
                   a.assignment_type::text AS assignment_type, a.status::text AS status,
                   a.priority, a.created_at, a.started_at, a.completed_at,
                   a.reassigned_count, u.display_name AS assigned_to, a.assigned_to AS assigned_user_id,
                   pu.display_name AS previous_assignee,
                   w.wave_id, w.wave_number,
                   e.equipment_id, e.code AS equipment_code,
                   e.equipment_type::text AS equipment_type_code,
                   e.check_digits AS equipment_digits,
                   CASE WHEN a.status = 'CANCELLED' THEN 'CANCELLED'
                        WHEN a.status = 'COMPLETE' THEN 'COMPLETE'
                        WHEN EXISTS (SELECT 1 FROM assignment_task t2
                                     WHERE t2.assignment_id = a.assignment_id
                                       AND t2.status = 'COMPLETE') THEN 'IN PROGRESS'
                        WHEN a.reassigned_count > 0 THEN 'REASSIGNED'
                        WHEN a.assigned_to IS NOT NULL THEN 'ASSIGNED'
                        ELSE 'PENDING' END AS display_status
            FROM assignment a
            LEFT JOIN app_user u ON u.user_id = a.assigned_to
            LEFT JOIN app_user pu ON pu.user_id = a.previous_assignee
            LEFT JOIN wave w ON w.wave_id = a.wave_id
            LEFT JOIN equipment e ON e.equipment_id = a.equipment_id
            WHERE a.assignment_id = ?
            """, assignmentId);
        a.put("lines", jdbc.queryForList("""
            SELECT t.task_id, t.seq, t.status::text AS status, t.qty,
                   t.check_digits, t.put_check_digits, t.cart_position, t.spoken_prompt,
                   lf.location_id AS from_id, lf.code AS from_code,
                   tag_of(lf.temp_zone::text) AS from_tag, css_of(lf.temp_zone::text) AS from_css,
                   lf.pick_sequence,
                   lt.location_id AS to_id, lt.code AS to_code,
                   CASE WHEN lt.location_id IS NULL THEN NULL
                        WHEN lt.loc_type::text = 'DROP' THEN 'DRP'
                        ELSE tag_of(lt.temp_zone::text) END AS to_tag,
                   css_of(lt.temp_zone::text) AS to_css,
                   it.item_id, it.sku::text AS sku, it.description AS item,
                   inv.lpn, inv.lot_number,
                   ac.order_id, o.order_number,
                   c.container_id, c.barcode AS tote_barcode, c.check_digits AS tote_digits,
                   ep.check_digits AS position_digits
            FROM assignment_task t
            LEFT JOIN location lf ON lf.location_id = t.from_location
            LEFT JOIN location lt ON lt.location_id = t.to_location
            LEFT JOIN item it ON it.item_id = t.item_id
            LEFT JOIN inventory inv ON inv.inventory_id = t.inventory_id
            LEFT JOIN assignment_container ac ON ac.assignment_id = t.assignment_id
                 AND ac.cart_position = t.cart_position
            LEFT JOIN customer_order o ON o.order_id = ac.order_id
            LEFT JOIN container c ON c.container_id = ac.container_id
            LEFT JOIN assignment a2 ON a2.assignment_id = t.assignment_id
            LEFT JOIN equipment_position ep ON ep.equipment_id = a2.equipment_id
                 AND ep.position_no = t.cart_position
            WHERE t.assignment_id = ?
            ORDER BY t.seq
            """, assignmentId));
        return a;
    }
}
