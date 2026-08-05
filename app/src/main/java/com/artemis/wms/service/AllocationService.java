package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Walks candidates in the effective_rotation order (policy cascade first,
 * else FEFO for expiry-tracked / FIFO otherwise, proximity tiebreaker).
 * Freshness cutoff = strictest of customer min-remaining-life, item ship
 * minimum, site/area policy ship minimum.
 *
 * The bypass, per the agreed policy: when the lot strict rotation would take
 * fails the cutoff, skip it, fill from the next closest qualifying date, and
 * raise a WARNING ROTATION_BYPASS alert scoped to the bypassed lot's Area
 * (falling back to Site) — the team gets told the system made that call.
 */
@Service
public class AllocationService {

    private final JdbcTemplate jdbc;
    private final PolicyService policy;
    private final AlertService alerts;

    public AllocationService(JdbcTemplate jdbc, PolicyService policy, AlertService alerts) {
        this.jdbc = jdbc; this.policy = policy; this.alerts = alerts;
    }

    @Transactional
    public Map<String, Object> allocate(UUID orderId) {
        Map<String, Object> order = jdbc.queryForMap("""
            SELECT o.site_id, o.status::text AS status, o.customer_id, c.min_shelf_life_days AS customer_min
            FROM customer_order o JOIN customer c ON c.customer_id = o.customer_id
            WHERE o.order_id = ?
            """, orderId);
        if (!"NEW".equals(order.get("status")))
            throw ApiException.conflict("Order is not in NEW status.");
        UUID siteId = (UUID) order.get("site_id");
        Integer customerMin = (Integer) order.get("customer_min");
        Integer policyShipMin = policy.effectiveMinShipDays(siteId);

        List<Map<String, Object>> lines = jdbc.queryForList("""
            SELECT l.order_line_id, l.item_id, i.sku, i.expiry_tracked,
                   i.min_shelf_life_ship_days AS item_ship_min,
                   l.ordered_qty - l.allocated_qty AS open_qty
            FROM customer_order_line l JOIN item i ON i.item_id = l.item_id
            WHERE l.order_id = ? AND l.ordered_qty > l.allocated_qty
            ORDER BY l.line_number
            """, orderId);

        List<String> messages = new ArrayList<>();
        boolean anyShort = false;

        for (Map<String, Object> line : lines) {
            UUID itemId = (UUID) line.get("item_id");
            BigDecimal open = (BigDecimal) line.get("open_qty");
            Integer itemShipMin = (Integer) line.get("item_ship_min");

            Integer cutoffDays = strictest(customerMin, itemShipMin, policyShipMin);
            LocalDate cutoffDate = cutoffDays == null ? null : LocalDate.now().plusDays(cutoffDays);

            String rotation = policy.effectiveRotation(itemId, siteId);
            String orderBy = switch (rotation) {
                case "FEFO" -> "expiration_date ASC NULLS LAST, pick_sequence ASC NULLS LAST";
                case "LIFO" -> "arrival_date DESC NULLS LAST, pick_sequence ASC NULLS LAST";
                case "NONE" -> "pick_sequence ASC NULLS LAST";
                default     -> "arrival_date ASC NULLS LAST, pick_sequence ASC NULLS LAST"; // FIFO
            };
            List<Map<String, Object>> candidates = jdbc.queryForList("""
                SELECT inventory_id, lot_number, expiration_date, available_qty, area_id
                FROM v_available_inventory
                WHERE site_id = ? AND item_id = ?
                ORDER BY """ + " " + orderBy, siteId, itemId);

            BigDecimal remaining = open;
            for (Map<String, Object> cand : candidates) {
                if (remaining.signum() <= 0) break;
                java.sql.Date expSql = (java.sql.Date) cand.get("expiration_date");
                LocalDate exp = expSql == null ? null : expSql.toLocalDate();
                if (cutoffDate != null && exp != null && exp.isBefore(cutoffDate)) {
                    // strict rotation says take it; the freshness rule disqualifies it — bypass
                    String msg = "ROTATION_BYPASS: lot " + cand.get("lot_number") + " (" + line.get("sku")
                            + ", expires " + exp + ") skipped — under the " + cutoffDays
                            + "-day freshness cutoff. Filled from the next qualifying date; aging stock needs disposition.";
                    messages.add(msg);
                    alerts.raise(siteId, (UUID) cand.get("area_id"), "ROTATION_BYPASS", "WARNING", msg,
                            orderId, itemId, (UUID) cand.get("inventory_id"));
                    continue;
                }
                BigDecimal avail = (BigDecimal) cand.get("available_qty");
                BigDecimal take = remaining.min(avail);
                jdbc.update("""
                    INSERT INTO allocation (order_line_id, inventory_id, qty) VALUES (?, ?, ?)
                    """, line.get("order_line_id"), cand.get("inventory_id"), take);
                // Flip status only when the LPN is FULLY allocated. Partially
                // allocated records must stay AVAILABLE so v_available_inventory
                // keeps offering the remainder (it nets out allocation rows).
                jdbc.update("""
                    UPDATE inventory SET status = 'ALLOCATED', updated_at = now()
                    WHERE inventory_id = ? AND qty <= COALESCE(
                        (SELECT sum(qty) FROM allocation WHERE inventory_id = ?), 0)
                    """, cand.get("inventory_id"), cand.get("inventory_id"));
                remaining = remaining.subtract(take);
            }
            jdbc.update("""
                UPDATE customer_order_line SET allocated_qty = ordered_qty - ? WHERE order_line_id = ?
                """, remaining, line.get("order_line_id"));
            if (remaining.signum() > 0) {
                anyShort = true;
                messages.add("SHORT: " + line.get("sku") + " short " + remaining.stripTrailingZeros().toPlainString()
                        + " — order stays NEW; re-allocate after receiving tops it off.");
            }
        }

        if (!anyShort) {
            jdbc.update("UPDATE customer_order SET status = 'ALLOCATED' WHERE order_id = ?", orderId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("status", anyShort ? "NEW" : "ALLOCATED");
        result.put("messages", messages);
        return result;
    }

    private Integer strictest(Integer... values) {
        Integer max = null;
        for (Integer v : values) if (v != null && (max == null || v > max)) max = v;
        return max;
    }
}
