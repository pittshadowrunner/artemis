package com.artemis.wms.outbound;

import com.artemis.wms.alerts.AlertService;
import com.artemis.wms.org.PolicyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Allocation policy: pick faces first, then reserve in rotation order
 * (FEFO/FIFO/LIFO per the item's effective policy). When customer
 * shelf-life terms disqualify the strict-rotation pallet, skip it, take
 * the next one that qualifies, mark the row as a bypass and raise a
 * ROTATION_BYPASS alert so the team knows older stock is sitting.
 */
@Service
public class AllocationService {
    private final JdbcTemplate jdbc;
    private final PolicyService policy;
    private final AlertService alerts;
    public AllocationService(JdbcTemplate jdbc, PolicyService policy, AlertService alerts) { this.jdbc = jdbc; this.policy = policy; this.alerts = alerts; }

    public record Result(BigDecimal allocated, BigDecimal shortfall, int bypasses) {}

    /** Allocate every unallocated line of the order that belongs to the given zone. */
    public Result allocateZoneOrder(UUID zoneOrderId) {
        Map<String, Object> z = jdbc.queryForMap("SELECT z.order_id, z.temp_zone::text AS temp_zone, o.site_id, o.requested_ship_date, c.min_shelf_life_days FROM zone_order z JOIN customer_order o ON o.order_id = z.order_id JOIN customer c ON c.customer_id = o.customer_id WHERE z.zone_order_id = ?", zoneOrderId);
        UUID siteId = (UUID) z.get("site_id");
        LocalDate shipDate = z.get("requested_ship_date") == null ? LocalDate.now() : ((java.sql.Date) z.get("requested_ship_date")).toLocalDate();
        Integer customerMin = (Integer) z.get("min_shelf_life_days");
        List<Map<String, Object>> lines = jdbc.queryForList("""
            SELECT ol.order_line_id, ol.item_id, ol.ordered_qty - ol.allocated_qty AS needed, i.sku::text AS sku, i.min_shelf_life_ship_days AS item_min
            FROM customer_order_line ol JOIN item i ON i.item_id = ol.item_id
            WHERE ol.order_id = ? AND i.temp_zone::text = ? AND ol.ordered_qty > ol.allocated_qty ORDER BY ol.line_number""",
            z.get("order_id"), z.get("temp_zone"));
        BigDecimal allocated = BigDecimal.ZERO, shortfall = BigDecimal.ZERO; int bypasses = 0;
        for (Map<String, Object> line : lines) {
            UUID itemId = (UUID) line.get("item_id");
            BigDecimal needed = (BigDecimal) line.get("needed");
            Integer minDays = strictest((Integer) line.get("item_min"), policy.minShipDays(siteId), customerMin);   // the customer, the site and the item all get a say; strictest wins
            String rotation = policy.rotation(itemId, siteId);
            List<Map<String, Object>> candidates = jdbc.queryForList("""
                SELECT inventory_id, lpn, available_qty, expiration_date, location_code, area_id
                FROM v_available_inventory WHERE site_id = ? AND item_id = ? AND available_qty > 0
                ORDER BY at_pick_face DESC,
                         CASE WHEN ? = 'FEFO' THEN expiration_date END ASC NULLS LAST,
                         CASE WHEN ? = 'LIFO' THEN arrival_date END DESC NULLS LAST,
                         arrival_date ASC, pick_sequence ASC NULLS LAST""", siteId, itemId, rotation, rotation);
            boolean strictSkipped = false;
            for (Map<String, Object> c : candidates) {
                if (needed.signum() <= 0) break;
                java.sql.Date expSql = (java.sql.Date) c.get("expiration_date");
                if (minDays != null && expSql != null && ChronoUnit.DAYS.between(shipDate, expSql.toLocalDate()) < minDays) {
                    strictSkipped = true;
                    continue;
                }
                BigDecimal take = needed.min((BigDecimal) c.get("available_qty"));
                jdbc.update("INSERT INTO allocation (order_line_id, inventory_id, qty, bypass) VALUES (?, ?, ?, ?)", line.get("order_line_id"), c.get("inventory_id"), take, strictSkipped);
                jdbc.update("UPDATE inventory SET status = 'ALLOCATED', updated_at = now() WHERE inventory_id = ? AND qty <= (SELECT sum(qty - picked_qty) FROM allocation WHERE inventory_id = ?)", c.get("inventory_id"), c.get("inventory_id"));
                if (strictSkipped) {
                    bypasses++;
                    alerts.raise(siteId, (UUID) c.get("area_id"), "ROTATION_BYPASS", "WARNING",
                        "Strict " + rotation + " bypassed for " + line.get("sku") + ": customer needs " + minDays + " days of shelf life; allocated " + c.get("lpn") + " at " + c.get("location_code") + " instead. Older stock is still on the shelf.",
                        "INVENTORY", (UUID) c.get("inventory_id"));
                    strictSkipped = false;
                }
                needed = needed.subtract(take); allocated = allocated.add(take);
            }
            jdbc.update("UPDATE customer_order_line SET allocated_qty = (SELECT COALESCE(sum(qty), 0) FROM allocation WHERE order_line_id = ?) WHERE order_line_id = ?", line.get("order_line_id"), line.get("order_line_id"));
            if (needed.signum() > 0) shortfall = shortfall.add(needed);
        }
        return new Result(allocated, shortfall, bypasses);
    }

    private static Integer strictest(Integer... vals) {
        Integer max = null;
        for (Integer v : vals) if (v != null && (max == null || v > max)) max = v;
        return max;
    }
}
