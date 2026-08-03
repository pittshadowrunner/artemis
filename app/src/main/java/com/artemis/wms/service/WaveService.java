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
        int maxOrders = req.get("maxOrders") == null ? 25 : Integer.parseInt(req.get("maxOrders").toString());

        if (orderIds.isEmpty()) {
            orderIds = autoSelect(siteId, waveType, str(req.get("routeCode")), maxOrders);
            if (orderIds.isEmpty()) throw ApiException.conflict("No ALLOCATED orders match this wave type.");
        }
        UUID waveId = UUID.randomUUID();
        String waveNumber = str(req.get("waveNumber"));
        if (waveNumber == null) {
            Long n = jdbc.queryForObject("SELECT count(*) + 1 FROM wave WHERE site_id = ?", Long.class, siteId);
            waveNumber = "W-" + java.time.LocalDate.now() + "-" + String.format("%03d", n);
        }
        jdbc.update("""
            INSERT INTO wave (wave_id, corporation_id, site_id, wave_number, wave_type, carrier_cutoff,
                route_code, area_id, planned_by)
            VALUES (?, ?, ?, ?, ?::wave_type, ?::timestamptz, ?, ?, ?)
            """, waveId, TenantContext.corp(), siteId, waveNumber, waveType,
            str(req.get("carrierCutoff")), str(req.get("routeCode")), uuid(req.get("areaId")),
            TenantContext.user());
        for (UUID orderId : orderIds)
            jdbc.update("INSERT INTO wave_order (wave_id, order_id) VALUES (?, ?)", waveId, orderId);
        return Map.of("waveId", waveId, "waveNumber", waveNumber, "orders", orderIds.size());
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

        List<Map<String, Object>> orders = jdbc.queryForList("""
            SELECT wo.order_id, COALESCE(min(l.pick_sequence), 0) AS path_start
            FROM wave_order wo
            LEFT JOIN customer_order_line col ON col.order_id = wo.order_id
            LEFT JOIN allocation al ON al.order_line_id = col.order_line_id
            LEFT JOIN inventory inv ON inv.inventory_id = al.inventory_id
            LEFT JOIN location l ON l.location_id = inv.location_id
            WHERE wo.wave_id = ?
            GROUP BY wo.order_id ORDER BY path_start
            """, waveId);

        List<UUID> assignments = new ArrayList<>();
        for (int chunk = 0; chunk < orders.size(); chunk += positions) {
            List<Map<String, Object>> batch = orders.subList(chunk, Math.min(chunk + positions, orders.size()));
            UUID assignmentId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO assignment (assignment_id, corporation_id, site_id, assignment_type, wave_id, equipment_id)
                VALUES (?, ?, ?, 'SELECTION', ?, ?)
                """, assignmentId, TenantContext.corp(), siteId, waveId, equipmentId);

            int position = 0;
            Map<UUID, Integer> orderPosition = new HashMap<>();
            for (Map<String, Object> o : batch) {
                position++;
                UUID orderId = (UUID) o.get("order_id");
                orderPosition.put(orderId, position);
                jdbc.update("""
                    INSERT INTO assignment_container (assignment_id, order_id, cart_position)
                    VALUES (?, ?, ?)
                    """, assignmentId, orderId, position);
            }

            // merged tasks across the chunk, sorted by pick_sequence — the walk order
            List<Map<String, Object>> picks = jdbc.queryForList("""
                SELECT col.order_id, al.allocation_id, al.inventory_id, al.qty, col.item_id,
                       inv.location_id, l.code AS loc_code, l.check_digits, l.pick_sequence
                FROM wave_order wo
                JOIN customer_order_line col ON col.order_id = wo.order_id
                JOIN allocation al ON al.order_line_id = col.order_line_id
                JOIN inventory inv ON inv.inventory_id = al.inventory_id
                JOIN location l ON l.location_id = inv.location_id
                WHERE wo.wave_id = ? AND wo.order_id = ANY(?)
                ORDER BY l.pick_sequence ASC NULLS LAST
                """, waveId,
                batch.stream().map(o -> (UUID) o.get("order_id")).toArray(UUID[]::new));

            int seq = 0;
            for (Map<String, Object> p : picks) {
                seq++;
                UUID orderId = (UUID) p.get("order_id");
                int cartPos = orderPosition.get(orderId);
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
            assignments.add(assignmentId);
        }
        jdbc.update("UPDATE wave SET status = 'RELEASED', released_at = now() WHERE wave_id = ?", waveId);
        jdbc.update("""
            UPDATE customer_order SET status = 'RELEASED'
            WHERE order_id IN (SELECT order_id FROM wave_order WHERE wave_id = ?)
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
}
