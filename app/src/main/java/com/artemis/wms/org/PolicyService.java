package com.artemis.wms.org;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.platform.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.artemis.wms.platform.Values.*;

/** Inventory-control policy cascade: set at Site or Area; smallest unit wins, field by field. */
@Service
public class PolicyService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    public PolicyService(JdbcTemplate jdbc, AccessService access) { this.jdbc = jdbc; this.access = access; }

    public Map<String, Object> effective(UUID nodeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT (effective_policy(?)).*", nodeId);
        Map<String, Object> p = rows.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(rows.get(0));
        p.remove("policy_id"); p.remove("created_at"); p.remove("updated_at");
        return p;
    }

    public Map<String, Object> declared(UUID nodeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT rotation::text AS rotation, min_shelf_life_receipt_days, min_shelf_life_ship_days, expiry_alert_days, allow_lot_mixing, allow_item_mixing, catch_weight_tolerance_pct, require_lot_capture, require_expiry_capture FROM inventory_policy WHERE org_node_id = ?", nodeId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** Upsert the declared policy at a node. Unspecified fields stay NULL (= inherit). */
    @Transactional
    public void set(UUID nodeId, Map<String, Object> p) {
        access.require(nodeId, Capabilities.ORG_MANAGE);
        String level = jdbc.queryForObject("SELECT level::text FROM org_node WHERE org_node_id = ?", String.class, nodeId);
        if (!List.of("SITE_LOCATION", "AREA").contains(level))
            throw ApiException.badRequest("Policies are set at Site or Area level.");
        jdbc.update("""
            INSERT INTO inventory_policy (corporation_id, org_node_id, rotation, min_shelf_life_receipt_days, min_shelf_life_ship_days,
                expiry_alert_days, allow_lot_mixing, allow_item_mixing, catch_weight_tolerance_pct, require_lot_capture, require_expiry_capture)
            SELECT corporation_id, ?, ?::rotation_strategy, ?, ?, ?, ?, ?, ?, ?, ? FROM org_node WHERE org_node_id = ?
            ON CONFLICT (org_node_id) DO UPDATE SET rotation = EXCLUDED.rotation,
                min_shelf_life_receipt_days = EXCLUDED.min_shelf_life_receipt_days, min_shelf_life_ship_days = EXCLUDED.min_shelf_life_ship_days,
                expiry_alert_days = EXCLUDED.expiry_alert_days, allow_lot_mixing = EXCLUDED.allow_lot_mixing, allow_item_mixing = EXCLUDED.allow_item_mixing,
                catch_weight_tolerance_pct = EXCLUDED.catch_weight_tolerance_pct, require_lot_capture = EXCLUDED.require_lot_capture,
                require_expiry_capture = EXCLUDED.require_expiry_capture, updated_at = now()""",
            nodeId, upper(p.get("rotation")), integer(p.get("minShelfLifeReceiptDays")), integer(p.get("minShelfLifeShipDays")),
            integer(p.get("expiryAlertDays")), bool(p.get("allowLotMixing")), bool(p.get("allowItemMixing")),
            number(p.get("catchWeightTolerancePct")), bool(p.get("requireLotCapture")), bool(p.get("requireExpiryCapture")), nodeId);
    }

    public Integer minReceiptDays(UUID nodeId) { return jdbc.queryForObject("SELECT (effective_policy(?)).min_shelf_life_receipt_days", Integer.class, nodeId); }
    public Integer minShipDays(UUID nodeId)    { return jdbc.queryForObject("SELECT (effective_policy(?)).min_shelf_life_ship_days", Integer.class, nodeId); }
    public boolean allowItemMixing(UUID nodeId) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT (effective_policy(?)).allow_item_mixing", Boolean.class, nodeId)); }
    public boolean allowLotMixing(UUID nodeId)  { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT (effective_policy(?)).allow_lot_mixing", Boolean.class, nodeId)); }
    public String rotation(UUID itemId, UUID nodeId) { return jdbc.queryForObject("SELECT effective_rotation(?, ?)::text", String.class, itemId, nodeId); }
}
