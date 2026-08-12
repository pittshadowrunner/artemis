package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.artemis.wms.service.LocationService.*;

@Service
public class WaveService {

    private final JdbcTemplate jdbc;

    public WaveService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Explicit orderIds, or type-appropriate auto-select. */
    @Transactional
    public Map<String, Object> create(Map<String, Object> req) {
        UUID siteId = uuid(req.get("siteId"));
        String waveType = str(req.get("waveType"));
        List<UUID> orderIds = uuidList(req.get("orderIds"));
        String tempZone = str(req.get("tempZone"));
        int maxOrders = req.get("maxOrders") == null ? 25 : Integer.parseInt(req.get("maxOrders").toString());

        if (orderIds.isEmpty()) {
            orderIds = autoSelect(siteId, waveType, str(req.get("routeCode")), maxOrders);
            if (orderIds.isEmpty()) throw ApiException.conflict("No ALLOCATED orders match this wave type.");
        }
        // A wave is zone-shaped. Derive the zone from the orders when not
        // given; refuse ambiguity rather than guess.
        if (tempZone == null) {
            List<String> zones = jdbc.queryForList("""
                SELECT DISTINCT temp_zone::text FROM zone_order WHERE order_id = ANY(?)
                """, String.class, (Object) orderIds.toArray(UUID[]::new));
            if (zones.size() != 1)
                throw ApiException.badRequest("These orders span " + zones.size()
                    + " temp zones — pass tempZone to say which zone this wave picks.");
            tempZone = zones.get(0);
        }
        UUID waveId = UUID.randomUUID();
        String waveNumber = str(req.get("waveNumber"));
        if (waveNumber == null) {
            waveNumber = jdbc.queryForObject(
                "SELECT 'W-' || to_char(now(), 'YYMMDD-HH24MI') || '-' || lpad(nextval('wave_number_seq')::text, 3, '0')",
                String.class);
        }
        jdbc.update("""
            INSERT INTO wave (wave_id, corporation_id, site_id, wave_number, wave_type, carrier_cutoff,
                route_code, area_id, planned_by, temp_zone)
            VALUES (?, ?, ?, ?, ?::wave_type, ?::timestamptz, ?, ?, ?, ?::temp_zone)
            """, waveId, TenantContext.corp(), siteId, waveNumber, waveType,
            str(req.get("carrierCutoff")), str(req.get("routeCode")), uuid(req.get("areaId")),
            TenantContext.user(), tempZone);
        int attached = 0;
        for (UUID orderId : orderIds) {
            jdbc.update("INSERT INTO wave_order (wave_id, order_id) VALUES (?, ?)", waveId, orderId);
            attached += jdbc.update("""
                UPDATE zone_order SET wave_id = ?
                WHERE order_id = ? AND temp_zone = ?::temp_zone AND wave_id IS NULL
                """, waveId, orderId, tempZone);
        }
        if (attached == 0)
            throw ApiException.conflict("None of these orders have unwaved " + tempZone + " zone orders.");
        return Map.of("waveId", waveId, "waveNumber", waveNumber, "tempZone", tempZone, "zoneOrders", attached);
    }

    private List<UUID> autoSelect(UUID siteId, String waveType, String routeCode, int maxOrders) {
        return switch (waveType) {
            case "SHIP_URGENCY", "CARRIER_CUTOFF" -> jdbc.queryForList("""
                SELECT order_id FROM customer_order
                WHERE site_id = ? AND status = 'ALLOCATED'
                ORDER BY requested_ship_date ASC NULLS LAST LIMIT ?
                """, UUID.class, siteId, maxOrders);
            case "PROXIMITY" -> jdbc.queryForList("""
                SELECT order_id FROM customer_order
                WHERE site_id = ? AND status = 'ALLOCATED'
                ORDER BY order_pick_span(order_id) ASC LIMIT ?
                """, UUID.class, siteId, maxOrders);
            case "ROUTE" -> jdbc.queryForList("""
                SELECT o.order_id FROM customer_order o
                JOIN customer c ON c.customer_id = o.customer_id
                WHERE o.site_id = ? AND o.status = 'ALLOCATED' AND c.route_code = ?
                ORDER BY c.stop_sequence DESC LIMIT ?
                """, UUID.class, siteId, routeCode, maxOrders);       // last stop loads first
            default -> jdbc.queryForList("""
                SELECT order_id FROM customer_order
                WHERE site_id = ? AND status = 'ALLOCATED'
                ORDER BY created_at LIMIT ?
                """, UUID.class, siteId, maxOrders);
        };
    }

    /**
     * Release is where batching happens. With a cart, batch size =
     * container_positions: orders sort by pick-path start and chunk N at a
     * time; each order maps to a cart position; tasks merge across the
     * chunk's orders and sort by pick_sequence — the walk order.
     */
    @Transactional
    public Map<String, Object> release(UUID waveId, String equipmentCode, String putMode) {
        Map<String, Object> wave = jdbc.queryForMap(
            "SELECT site_id, status::text AS status FROM wave WHERE wave_id = ?", waveId);
        if (!"PLANNED".equals(wave.get("status"))) throw ApiException.conflict("Wave is not PLANNED.");
        UUID siteId = (UUID) wave.get("site_id");

        Integer positions = 1;
        UUID equipmentId = null;
        if (equipmentCode != null) {
            Map<String, Object> eq = jdbc.queryForMap(
                "SELECT equipment_id, container_positions FROM equipment WHERE site_id = ? AND code = ? AND active",
                siteId, equipmentCode);
            equipmentId = (UUID) eq.get("equipment_id");
            positions = eq.get("container_positions") == null ? 1 : (Integer) eq.get("container_positions");
        }
        boolean shippingContainer = "SHIPPING_CONTAINER".equals(putMode);

        List<Map<String, Object>> zoneOrders = jdbc.queryForList("""
            SELECT zo.zone_order_id, COALESCE(min(l.pick_sequence), 0) AS path_start
            FROM zone_order zo
            JOIN wave w ON w.wave_id = zo.wave_id
            LEFT JOIN customer_order_line col ON col.order_id = zo.order_id
            LEFT JOIN item i ON i.item_id = col.item_id AND i.temp_zone = w.temp_zone
            LEFT JOIN allocation al ON al.order_line_id = col.order_line_id AND i.item_id IS NOT NULL
            LEFT JOIN inventory inv ON inv.inventory_id = al.inventory_id
            LEFT JOIN location l ON l.location_id = inv.location_id
            WHERE zo.wave_id = ?
            GROUP BY zo.zone_order_id ORDER BY path_start
            """, waveId);

        List<UUID> assignments = new ArrayList<>();
        if (equipmentId == null) {
            // Pooled release: the wave and its zone orders become RELEASED
            // work, but no assignments exist yet. Put-to stays blank by
            // definition — positions only make sense once equipment is known.
        } else {
            for (int chunk = 0; chunk < zoneOrders.size(); chunk += positions) {
                List<Map<String, Object>> batch = zoneOrders.subList(chunk, Math.min(chunk + positions, zoneOrders.size()));
                assignments.add(buildOne(siteId, waveId,
                    batch.stream().map(o -> (UUID) o.get("zone_order_id")).toList(),
                    equipmentId, shippingContainer));
            }
        }
        jdbc.update("UPDATE wave SET status = 'RELEASED', released_at = now() WHERE wave_id = ?", waveId);
        jdbc.update("""
            UPDATE customer_order SET status = 'RELEASED'
            WHERE order_id IN (SELECT order_id FROM wave_order WHERE wave_id = ?)
            """, waveId);
        // Priority 1-10: cold chain outranks dry. Coldest item in each
        // assignment sets its floor (FRZ 8, CHL 7, DRY 5).
        jdbc.update("""
            UPDATE assignment a SET priority = sub.p FROM (
                SELECT t.assignment_id,
                       max(CASE WHEN it.temp_zone IN ('FROZEN','DEEP_FROZEN') THEN 8
                                WHEN it.temp_zone = 'REFRIGERATED' THEN 7 ELSE 5 END) AS p
                FROM assignment_task t JOIN item it ON it.item_id = t.item_id
                WHERE t.assignment_id IN (SELECT assignment_id FROM assignment WHERE wave_id = ?)
                GROUP BY t.assignment_id) sub
            WHERE a.assignment_id = sub.assignment_id
            """, waveId);

        return Map.of("waveId", waveId, "assignments", assignments, "putMode",
                shippingContainer ? "SHIPPING_CONTAINER" : "TOTE");
    }

    /** Put check digits from equipment_position, or derive per-order if the cart has none registered. */
    private String putDigits(UUID equipmentId, int cartPosition, boolean shippingContainer) {
        if (shippingContainer) return null;      // verified against the inducted LPN instead
        if (equipmentId != null) {
            List<String> digits = jdbc.queryForList(
                "SELECT check_digits FROM equipment_position WHERE equipment_id = ? AND position_no = ?",
                String.class, equipmentId, cartPosition);
            if (!digits.isEmpty()) return digits.get(0);
        }
        return String.format("%02d", (cartPosition * 37) % 100);
    }

    @SuppressWarnings("unchecked")
    private List<UUID> uuidList(Object o) {
        if (o == null) return List.of();
        return ((List<Object>) o).stream().map(x -> UUID.fromString(x.toString())).toList();
    }
    /** One selection assignment from an ordered set of orders: containers by
     *  position, merged tasks in pick-path order. Shared by wave release and
     *  manual combine. */
    private UUID buildOne(UUID siteId, UUID waveId, List<UUID> zoneOrderIds,
                          UUID equipmentId, boolean shippingContainer) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO assignment (assignment_id, corporation_id, site_id, assignment_type, wave_id, equipment_id, assignment_number)
            VALUES (?, ?, ?, 'SELECTION', ?, ?, 'A-' || to_char(now(),'YYMMDD') || '-' || lpad(nextval('assignment_number_seq')::text, 5, '0'))
            """, assignmentId, TenantContext.corp(), siteId, waveId, equipmentId);

        int position = 0;
        Map<UUID, Integer> zoPosition = new HashMap<>();
        for (UUID zoId : zoneOrderIds) {
            position++;
            zoPosition.put(zoId, position);
            jdbc.update("""
                INSERT INTO assignment_container (assignment_id, order_id, cart_position)
                SELECT ?, order_id, ? FROM zone_order WHERE zone_order_id = ?
                """, assignmentId, position, zoId);
            jdbc.update("UPDATE zone_order SET assignment_id = ? WHERE zone_order_id = ?",
                assignmentId, zoId);
        }

        // Picks: only this zone order's slice of its parent order — the
        // lines whose items live in the zone order's temp zone.
        List<Map<String, Object>> picks = jdbc.queryForList("""
            SELECT zo.zone_order_id, al.allocation_id, al.inventory_id, al.qty, col.item_id,
                   inv.location_id, l.code AS loc_code, l.check_digits, l.pick_sequence
            FROM zone_order zo
            JOIN customer_order_line col ON col.order_id = zo.order_id
            JOIN item i ON i.item_id = col.item_id AND i.temp_zone = zo.temp_zone
            JOIN allocation al ON al.order_line_id = col.order_line_id
            JOIN inventory inv ON inv.inventory_id = al.inventory_id
            JOIN location l ON l.location_id = inv.location_id
            WHERE zo.zone_order_id = ANY(?)
            ORDER BY l.pick_sequence ASC NULLS LAST
            """, (Object) zoneOrderIds.toArray(UUID[]::new));

        int seq = 0;
        for (Map<String, Object> p : picks) {
            seq++;
            UUID zoId = (UUID) p.get("zone_order_id");
            int cartPos = zoPosition.get(zoId);
            String putDigits = putDigits(equipmentId, cartPos, shippingContainer);
            String prompt = "Pick " + p.get("qty") + " from "
                    + String.valueOf(p.get("loc_code")).replace("-", " ")
                    + ", check " + p.get("check_digits")
                    + (shippingContainer ? ", put to order container" : ", put to position " + cartPos + ", say " + putDigits);
            jdbc.update("""
                INSERT INTO assignment_task (assignment_id, seq, inventory_id, item_id, from_location,
                    qty, check_digits, put_check_digits, cart_position, spoken_prompt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, assignmentId, seq, p.get("inventory_id"), p.get("item_id"), p.get("location_id"),
                p.get("qty"), p.get("check_digits"), putDigits, cartPos, prompt);
        }
        return assignmentId;
    }

    /** Manual combine: released zone orders (one zone, by construction)
     *  + chosen equipment -> one assignment, optionally dispatched to an
     *  operator with an explicit priority. */
    public Map<String, Object> combine(UUID siteId, List<UUID> zoneOrderIds, String equipmentCode,
                                       String userEmail, Integer priority) {
        if (zoneOrderIds == null || zoneOrderIds.isEmpty())
            throw ApiException.badRequest("Pick at least one released zone order.");
        Map<String, Object> eq = jdbc.queryForMap(
            "SELECT equipment_id, container_positions FROM equipment WHERE site_id = ? AND code = ? AND active",
            siteId, equipmentCode);
        UUID equipmentId = (UUID) eq.get("equipment_id");
        int positions = eq.get("container_positions") == null ? 1 : (Integer) eq.get("container_positions");
        if (zoneOrderIds.size() > positions)
            throw ApiException.badRequest("That unit has " + positions + " position(s) — pick at most that many zone orders.");

        List<String> zones = jdbc.queryForList("""
            SELECT DISTINCT temp_zone::text FROM zone_order WHERE zone_order_id = ANY(?)
            """, String.class, (Object) zoneOrderIds.toArray(UUID[]::new));
        if (zones.size() != 1)
            throw ApiException.conflict("An assignment stays in one zone — those zone orders span " + zones.size() + ".");
        Integer notReleased = jdbc.queryForObject("""
            SELECT count(*) FROM zone_order zo
            LEFT JOIN wave w ON w.wave_id = zo.wave_id
            WHERE zo.zone_order_id = ANY(?) AND (w.status IS DISTINCT FROM 'RELEASED')
            """, Integer.class, (Object) zoneOrderIds.toArray(UUID[]::new));
        if (notReleased != null && notReleased > 0)
            throw ApiException.conflict("Only zone orders in a RELEASED wave can be combined into an assignment.");
        Integer alreadyBuilt = jdbc.queryForObject("""
            SELECT count(*) FROM zone_order WHERE zone_order_id = ANY(?) AND assignment_id IS NOT NULL
            """, Integer.class, (Object) zoneOrderIds.toArray(UUID[]::new));
        if (alreadyBuilt != null && alreadyBuilt > 0)
            throw ApiException.conflict("One of those zone orders is already on an assignment.");

        UUID waveId = jdbc.queryForObject("""
            SELECT max(wave_id::text)::uuid FROM zone_order WHERE zone_order_id = ANY(?)
            """, UUID.class, (Object) zoneOrderIds.toArray(UUID[]::new));

        // sort by pick-path start so positions follow the walk
        List<UUID> ordered = jdbc.queryForList("""
            SELECT zo.zone_order_id
            FROM zone_order zo
            JOIN customer_order_line col ON col.order_id = zo.order_id
            JOIN item i ON i.item_id = col.item_id AND i.temp_zone = zo.temp_zone
            JOIN allocation al ON al.order_line_id = col.order_line_id
            JOIN inventory inv ON inv.inventory_id = al.inventory_id
            LEFT JOIN location l ON l.location_id = inv.location_id
            WHERE zo.zone_order_id = ANY(?)
            GROUP BY zo.zone_order_id ORDER BY COALESCE(min(l.pick_sequence), 0)
            """, UUID.class, (Object) zoneOrderIds.toArray(UUID[]::new));

        UUID assignmentId = buildOne(siteId, waveId, ordered, equipmentId, false);
        jdbc.update("""
            UPDATE assignment a SET priority = COALESCE(?, sub.p) FROM (
                SELECT max(CASE WHEN it.temp_zone IN ('FROZEN','DEEP_FROZEN') THEN 8
                                WHEN it.temp_zone = 'REFRIGERATED' THEN 7 ELSE 5 END) AS p
                FROM assignment_task t JOIN item it ON it.item_id = t.item_id
                WHERE t.assignment_id = ?) sub
            WHERE a.assignment_id = ?
            """, priority, assignmentId, assignmentId);
        if (userEmail != null && !userEmail.isBlank()) {
            jdbc.update("""
                UPDATE assignment SET assigned_to =
                    (SELECT user_id FROM app_user WHERE email = ?::citext), status = 'ASSIGNED'
                WHERE assignment_id = ?
                """, userEmail.trim(), assignmentId);
        }
        return Map.of("assignmentId", assignmentId, "orders", ordered.size());
    }
}