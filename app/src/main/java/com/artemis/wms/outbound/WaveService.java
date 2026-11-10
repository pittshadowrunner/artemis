package com.artemis.wms.outbound;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static com.artemis.wms.platform.Values.*;

/**
 * Waves. Planning picks open zone orders of one temp zone by the wave
 * type's own ordering; release allocates them and cuts SELECTION
 * assignments sized to the cart. Everything a picker will say or hear
 * is generated here once, so voice and screen never disagree.
 */
@Service
public class WaveService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final AllocationService allocation;
    public WaveService(JdbcTemplate jdbc, AccessService access, AllocationService allocation) { this.jdbc = jdbc; this.access = access; this.allocation = allocation; }

    /**
     * spec: {type, tempZone, batchSize?, maxOrders?, orderNumbers?[], routeCode?, customer?, cutoff?, area?}
     */
    @Transactional
    public Map<String, Object> plan(UUID siteId, Map<String, Object> spec) {
        UUID corp = access.requireTenant();
        access.require(siteId, Capabilities.WAVE_PLAN);
        String type = upper(spec.get("type")), zone = upper(spec.get("tempZone"));
        if (type == null || zone == null) throw ApiException.badRequest("type and tempZone are required — waves are single-zone.");
        UUID waveId = UUID.randomUUID();
        UUID customerId = spec.get("customer") == null ? null : jdbc.queryForObject("SELECT customer_id FROM customer WHERE corporation_id = ? AND code = ?", UUID.class, corp, upper(spec.get("customer")));
        jdbc.update("""
            INSERT INTO wave (wave_id, corporation_id, site_id, wave_number, wave_type, temp_zone, batch_size, carrier_cutoff, route_code, customer_id, planned_by)
            VALUES (?, ?, ?, next_wave_number(), ?::wave_type, ?::temp_zone, COALESCE(?, 6), ?::timestamptz, ?, ?, ?)""",
            waveId, corp, siteId, type, zone, integer(spec.get("batchSize")), str(spec.get("cutoff")), upper(spec.get("routeCode")), customerId, TenantContext.user());

        List<String> explicit = strings(spec.get("orderNumbers"));
        Integer max = integer(spec.get("maxOrders"));
        String order = switch (type) {
            case "RUSH"           -> "o.rush DESC, o.priority DESC, o.requested_ship_date NULLS LAST";
            case "SHIP_URGENCY"   -> "o.requested_ship_date NULLS LAST, o.priority DESC";
            case "CARRIER_CUTOFF" -> "o.requested_ship_date NULLS LAST, o.priority DESC";
            case "ROUTE"          -> "o.stop_sequence DESC NULLS LAST";     // load reverse stop order
            case "PROXIMITY"      -> "(SELECT min(l.pick_sequence) FROM customer_order_line ol JOIN v_available_inventory l ON l.item_id = ol.item_id AND l.site_id = o.site_id WHERE ol.order_id = o.order_id) NULLS LAST, o.priority DESC";
            default               -> "o.priority DESC, o.requested_ship_date NULLS LAST, o.order_number";
        };
        List<Object> args = new ArrayList<>(List.of(siteId, zone));
        StringBuilder where = new StringBuilder("z.wave_id IS NULL AND o.site_id = ? AND z.temp_zone::text = ? AND o.status NOT IN ('SHIPPED','CANCELLED')");   // other zones of the order may already be moving
        if (!explicit.isEmpty()) { where.append(" AND o.order_number = ANY(?::text[])"); args.add(explicit.stream().map(String::toUpperCase).toArray(String[]::new)); }
        if (type.equals("RUSH")) where.append(" AND o.rush");
        if (type.equals("ROUTE")) { where.append(" AND o.route_code = ?"); args.add(upper(spec.get("routeCode"))); }
        if (type.equals("CARRIER_CUTOFF")) { where.append(" AND o.requested_ship_date <= ?::timestamptz::date"); args.add(str(spec.get("cutoff"))); }
        if (type.equals("SINGLE_CUSTOMER")) { where.append(" AND o.customer_id = ?"); args.add(customerId); }
        if (spec.get("area") != null) { where.append(" AND EXISTS (SELECT 1 FROM customer_order_line ol JOIN v_available_inventory v ON v.item_id = ol.item_id AND v.site_id = o.site_id JOIN org_node a ON a.org_node_id = v.area_id WHERE ol.order_id = o.order_id AND a.code = ?)"); args.add(upper(spec.get("area"))); }
        if (max != null) args.add(max);
        List<UUID> zoneOrders = jdbc.queryForList("SELECT z.zone_order_id FROM zone_order z JOIN customer_order o ON o.order_id = z.order_id WHERE " + where + " ORDER BY " + order + (max != null ? " LIMIT ?" : ""), UUID.class, args.toArray());
        for (UUID zo : zoneOrders) jdbc.update("UPDATE zone_order SET wave_id = ? WHERE zone_order_id = ?", waveId, zo);
        return get(waveId);
    }

    @Transactional
    public Map<String, Object> release(UUID waveId, String equipmentCode) { return release(waveId, equipmentCode, false); }

    /**
     * Allocate, then either cut assignments now (batch = one cart of zone
     * orders) or — manual — leave the released zone orders on the floor to be
     * built by hand or handed to whoever asks for work next.
     */
    @Transactional
    public Map<String, Object> release(UUID waveId, String equipmentCode, boolean manual) {
        Map<String, Object> w = jdbc.queryForMap("SELECT site_id, corporation_id, status::text AS status, temp_zone::text AS temp_zone, batch_size FROM wave WHERE wave_id = ?", waveId);
        UUID siteId = (UUID) w.get("site_id");
        access.require(siteId, Capabilities.WAVE_PLAN);
        if (!"PLANNED".equals(w.get("status"))) throw ApiException.conflict("Wave is " + w.get("status") + ", not PLANNED.");
        List<UUID> zoneOrders = jdbc.queryForList("SELECT z.zone_order_id FROM zone_order z JOIN customer_order o ON o.order_id = z.order_id WHERE z.wave_id = ? ORDER BY o.priority DESC, o.stop_sequence DESC NULLS LAST, o.order_number", UUID.class, waveId);
        if (zoneOrders.isEmpty()) throw ApiException.conflict("Wave has no zone orders.");

        BigDecimal shortfall = BigDecimal.ZERO; int bypasses = 0;
        for (UUID zo : zoneOrders) {
            AllocationService.Result r = allocation.allocateZoneOrder(zo);
            shortfall = shortfall.add(r.shortfall()); bypasses += r.bypasses();
        }
        jdbc.update("UPDATE customer_order o SET status = 'ALLOCATED', updated_at = now() WHERE o.status = 'NEW' AND EXISTS (SELECT 1 FROM zone_order z WHERE z.order_id = o.order_id AND z.wave_id = ?)", waveId);

        Integer batch = (Integer) w.get("batch_size");
        UUID equipmentId = null;
        if (equipmentCode != null) {
            Map<String, Object> eq = jdbc.queryForMap("SELECT equipment_id, container_positions FROM equipment WHERE site_id = ? AND code = ?", siteId, equipmentCode.toUpperCase());
            equipmentId = (UUID) eq.get("equipment_id");
            if (eq.get("container_positions") != null) batch = (Integer) eq.get("container_positions");   // the cart decides
        }
        int assignments = 0;
        if (!manual) for (int i = 0; i < zoneOrders.size(); i += batch) {
            cutAssignment(siteId, (UUID) w.get("corporation_id"), waveId, (String) w.get("temp_zone"), equipmentId, zoneOrders.subList(i, Math.min(i + batch, zoneOrders.size())));
            assignments++;
        }
        jdbc.update("UPDATE wave SET status = 'RELEASED', released_at = now() WHERE wave_id = ?", waveId);
        Map<String, Object> out = new LinkedHashMap<>(get(waveId));
        out.put("release", Map.of("assignmentsCut", assignments, "shortfall", shortfall, "bypasses", bypasses, "leftToBuild", manual ? zoneOrders.size() : 0));
        return out;
    }

    /** Released zone orders with no assignment yet, best first. */
    public List<Map<String, Object>> unbuilt(UUID siteId, String zone) {
        return jdbc.queryForList("""
            SELECT u.zone_order_id, u.temp_zone::text AS temp_zone, u.wave_id, u.wave_number, u.batch_size, u.order_number, u.rush, u.priority, u.requested_ship_date,
                   u.route_code, u.stop_sequence, u.customer, u.customer_name, u.work_priority, u.picks
            FROM v_unbuilt_zone_order u WHERE u.site_id = ? AND (?::text IS NULL OR u.temp_zone::text = ?)
            ORDER BY u.work_priority DESC, u.rush DESC, u.priority DESC, u.requested_ship_date NULLS LAST, u.order_number""", siteId, zone, zone);
    }

    /**
     * Build one selection assignment by hand from released zone orders: same
     * zone, same wave, and no more of them than the unit has positions.
     */
    @Transactional
    public Map<String, Object> build(UUID siteId, List<UUID> zoneOrderIds, String equipmentCode) {
        access.require(siteId, Capabilities.WAVE_PLAN);
        return buildUnchecked(siteId, zoneOrderIds, equipmentCode);
    }

    /** The build itself; callers have already decided the requester may have this work. */
    @Transactional
    public Map<String, Object> buildUnchecked(UUID siteId, List<UUID> zoneOrderIds, String equipmentCode) {
        if (zoneOrderIds == null || zoneOrderIds.isEmpty()) throw ApiException.badRequest("Tick at least one zone order to build an assignment.");
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(zoneOrderIds));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UUID id : ids) {
            jdbc.queryForList("SELECT 1 FROM zone_order WHERE zone_order_id = ? FOR UPDATE", id);   // two builders can't take the same order
            List<Map<String, Object>> r = jdbc.queryForList("SELECT zone_order_id, corporation_id, temp_zone::text AS temp_zone, wave_id, wave_number, order_number FROM v_unbuilt_zone_order WHERE zone_order_id = ? AND site_id = ?", id, siteId);
            if (r.isEmpty()) throw ApiException.conflict("One of those zone orders is no longer waiting — it was built, dropped or its wave isn't released. Refresh and try again.");
            rows.add(r.get(0));
        }
        Map<String, Object> first = rows.get(0);
        for (Map<String, Object> r : rows) {
            if (!first.get("temp_zone").equals(r.get("temp_zone"))) throw ApiException.conflict("An assignment is one temperature zone: " + first.get("order_number") + " is " + first.get("temp_zone") + ", " + r.get("order_number") + " is " + r.get("temp_zone") + ".");
            if (!first.get("wave_id").equals(r.get("wave_id"))) throw ApiException.conflict("An assignment belongs to one wave: " + first.get("order_number") + " is in " + first.get("wave_number") + ", " + r.get("order_number") + " is in " + r.get("wave_number") + ".");
        }
        UUID equipmentId = null; String code = null;
        if (equipmentCode != null && !equipmentCode.isBlank()) {
            Map<String, Object> eq;
            try { eq = jdbc.queryForMap("SELECT equipment_id, code, container_positions, active FROM equipment WHERE site_id = ? AND code = ?", siteId, equipmentCode.trim().toUpperCase()); }
            catch (Exception e) { throw ApiException.notFound("No unit " + equipmentCode.toUpperCase() + " at this site."); }
            if (!Boolean.TRUE.equals(eq.get("active"))) throw ApiException.conflict(eq.get("code") + " is out of service.");
            Integer positions = (Integer) eq.get("container_positions");
            if (positions != null && ids.size() > positions) throw ApiException.conflict(eq.get("code") + " has " + positions + " positions; you ticked " + ids.size() + " zone orders.");
            equipmentId = (UUID) eq.get("equipment_id"); code = (String) eq.get("code");
        }
        UUID assignmentId = cutAssignment(siteId, (UUID) first.get("corporation_id"), (UUID) first.get("wave_id"), (String) first.get("temp_zone"), equipmentId, ids);
        Map<String, Object> a = jdbc.queryForMap("SELECT assignment_number, status::text AS status FROM assignment WHERE assignment_id = ?", assignmentId);
        if ("CANCELLED".equals(a.get("status"))) throw ApiException.conflict("Nothing is allocated for those zone orders, so there is nothing to pick.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assignmentId", assignmentId); out.put("assignmentNumber", a.get("assignment_number")); out.put("zoneOrders", ids.size());
        out.put("equipment", code); out.put("tempZone", first.get("temp_zone")); out.put("waveNumber", first.get("wave_number"));
        return out;
    }

    private UUID cutAssignment(UUID siteId, UUID corp, UUID waveId, String zone, UUID equipmentId, List<UUID> zoneOrders) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO assignment (assignment_id, corporation_id, site_id, assignment_number, assignment_type, priority, temp_zone, wave_id, equipment_id)
            VALUES (?, ?, ?, next_assignment_number(), 'SELECTION', default_priority('SELECTION', ?::temp_zone), ?::temp_zone, ?, ?)""",
            assignmentId, corp, siteId, zone, zone, waveId, equipmentId);
        int pos = 0;
        for (UUID zo : zoneOrders) {
            pos++;
            String putDigits = equipmentId == null ? String.format("%02d", (Math.abs(zo.hashCode()) % 100))
                : jdbc.queryForObject("SELECT COALESCE((SELECT check_digits FROM equipment_position WHERE equipment_id = ? AND position_no = ?), lpad((abs(hashtext(?::text)) % 100)::text, 2, '0'))", String.class, equipmentId, pos, zo);
            jdbc.update("INSERT INTO assignment_container (assignment_id, zone_order_id, cart_position, put_check_digits) VALUES (?, ?, ?, ?)", assignmentId, zo, pos, putDigits);
            jdbc.update("UPDATE zone_order SET assignment_id = ? WHERE zone_order_id = ?", assignmentId, zo);
        }
        // tasks: every open allocation of these zone orders, in pick-path order
        List<Map<String, Object>> picks = jdbc.queryForList("""
            SELECT a.allocation_id, a.inventory_id, a.qty - a.picked_qty AS qty, inv.location_id, inv.lpn, l.code, l.check_digits, l.pick_sequence,
                   ol.item_id, i.sku::text AS sku, ac.cart_position, ac.put_check_digits
            FROM assignment_container ac
            JOIN zone_order z ON z.zone_order_id = ac.zone_order_id
            JOIN customer_order_line ol ON ol.order_id = z.order_id
            JOIN item i ON i.item_id = ol.item_id AND i.temp_zone = z.temp_zone
            JOIN allocation a ON a.order_line_id = ol.order_line_id AND a.picked_qty < a.qty
            JOIN inventory inv ON inv.inventory_id = a.inventory_id
            JOIN location l ON l.location_id = inv.location_id
            WHERE ac.assignment_id = ?
              AND NOT EXISTS (SELECT 1 FROM assignment_task t WHERE t.allocation_id = a.allocation_id AND t.status <> 'CANCELLED')
            ORDER BY l.pick_sequence, ac.cart_position""", assignmentId);
        int seq = 0;
        for (Map<String, Object> p : picks) {
            seq++;
            String prompt = "Pick " + ((BigDecimal) p.get("qty")).stripTrailingZeros().toPlainString() + " from " + String.valueOf(p.get("code")).replace("-", " ")
                + ", check " + p.get("check_digits") + ", put position " + p.get("cart_position") + ", check " + p.get("put_check_digits");
            jdbc.update("""
                INSERT INTO assignment_task (assignment_id, seq, inventory_id, item_id, from_location, qty, check_digits, put_check_digits, spoken_prompt, allocation_id, cart_position)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                assignmentId, seq, p.get("inventory_id"), p.get("item_id"), p.get("location_id"), p.get("qty"), p.get("check_digits"), p.get("put_check_digits"), prompt, p.get("allocation_id"), p.get("cart_position"));
        }
        if (seq == 0) jdbc.update("UPDATE assignment SET status = 'CANCELLED', updated_at = now() WHERE assignment_id = ?", assignmentId);   // nothing allocatable
        return assignmentId;
    }

    public List<Map<String, Object>> list(UUID siteId) {
        return jdbc.queryForList("SELECT * FROM v_wave_progress WHERE site_id = ? ORDER BY created_at DESC", siteId);
    }

    public Map<String, Object> get(UUID waveId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM v_wave_progress WHERE wave_id = ?", waveId);
        if (rows.isEmpty()) throw ApiException.notFound("Wave not found.");
        Map<String, Object> w = new LinkedHashMap<>(rows.get(0));
        w.put("zoneOrders", jdbc.queryForList("""
            SELECT o.order_number, c.code AS customer, c.name AS customer_name, o.rush, o.priority, o.requested_ship_date, o.route_code, o.stop_sequence,
                   a.assignment_number, a.status::text AS assignment_status, a.priority AS assignment_priority, u.display_name AS operator, a.assigned_to AS operator_id,
                   ac.cart_position, z.dropped_at, dl.code AS drop_location
            FROM zone_order z JOIN customer_order o ON o.order_id = z.order_id JOIN customer c ON c.customer_id = o.customer_id
            LEFT JOIN assignment a ON a.assignment_id = z.assignment_id LEFT JOIN assignment_container ac ON ac.zone_order_id = z.zone_order_id
            LEFT JOIN app_user u ON u.user_id = a.assigned_to LEFT JOIN location dl ON dl.location_id = z.drop_location_id
            WHERE z.wave_id = ? ORDER BY a.assignment_number, ac.cart_position, o.order_number""", waveId));
        w.put("assignments", jdbc.queryForList("""
            SELECT a.assignment_id, a.assignment_number, a.status::text AS status, a.priority, e.code AS equipment, u.display_name AS assigned_to, a.assigned_to AS operator_id,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id) AS tasks,
                   (SELECT count(*) FROM assignment_task t WHERE t.assignment_id = a.assignment_id AND t.status = 'COMPLETE') AS done
            FROM assignment a LEFT JOIN equipment e ON e.equipment_id = a.equipment_id LEFT JOIN app_user u ON u.user_id = a.assigned_to
            WHERE a.wave_id = ? ORDER BY a.assignment_number""", waveId));
        return w;
    }
}
