package com.artemis.wms.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads the effective_policy cascade — nearest non-null value per field. */
@Service
public class PolicyService {

    private final JdbcTemplate jdbc;

    public PolicyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> effective(UUID nodeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT (effective_policy(?)).*", nodeId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Integer effectiveMinReceiptDays(UUID nodeId) {
        return jdbc.queryForObject(
            "SELECT (effective_policy(?)).min_shelf_life_receipt_days", Integer.class, nodeId);
    }

    public Integer effectiveMinShipDays(UUID nodeId) {
        return jdbc.queryForObject(
            "SELECT (effective_policy(?)).min_shelf_life_ship_days", Integer.class, nodeId);
    }

    public String effectiveRotation(UUID itemId, UUID nodeId) {
        return jdbc.queryForObject(
            "SELECT effective_rotation(?, ?)::text", String.class, itemId, nodeId);
    }

    public boolean allowItemMixing(UUID nodeId) {
        Boolean b = jdbc.queryForObject(
            "SELECT (effective_policy(?)).allow_item_mixing", Boolean.class, nodeId);
        return b == null || b;                          // default: items may share a slot
    }

    public boolean allowLotMixing(UUID nodeId) {
        Boolean b = jdbc.queryForObject(
            "SELECT (effective_policy(?)).allow_lot_mixing", Boolean.class, nodeId);
        return b != null && b;                          // default: lots may not
    }
}
