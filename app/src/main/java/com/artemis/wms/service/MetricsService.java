package com.artemis.wms.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Snapshot-on-load reads over the seven dashboard views. No auto-refresh, per spec. */
@Service
public class MetricsService {

    private final JdbcTemplate jdbc;

    public MetricsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> pickFaceVelocity(UUID siteId) {
        return jdbc.queryForList("SELECT site_id, location_id, location_code, item_id, sku::text AS sku, day, lines, visits, cases FROM v_pick_face_velocity WHERE site_id = ? ORDER BY lines DESC", siteId);
    }
    public List<Map<String, Object>> receivingProgress(UUID siteId) {
        return jdbc.queryForList("SELECT * FROM v_receiving_progress WHERE site_id = ?", siteId);
    }
    public List<Map<String, Object>> shippingProgress(UUID siteId) {
        return jdbc.queryForList("SELECT * FROM v_shipping_progress WHERE site_id = ?", siteId);
    }
    public List<Map<String, Object>> waveProgress(UUID siteId) {
        return jdbc.queryForList("SELECT * FROM v_wave_progress WHERE site_id = ?", siteId);
    }
    public List<Map<String, Object>> replenPressure(UUID siteId) {
        return jdbc.queryForList("SELECT site_id, location_id, location_code, replen_item_id, sku::text AS sku, on_hand, replen_trigger_qty, replen_max_qty FROM v_replen_pressure WHERE site_id = ?", siteId);
    }
    public List<Map<String, Object>> laborProductivity(UUID siteId) {
        return jdbc.queryForList("SELECT * FROM v_labor_productivity WHERE site_id = ? ORDER BY cases DESC", siteId);
    }
    public List<Map<String, Object>> laborSelf(UUID siteId, UUID userId) {
        return jdbc.queryForList(
            "SELECT * FROM v_labor_productivity WHERE site_id = ? AND user_id = ?", siteId, userId);
    }
    public List<Map<String, Object>> expiryRisk(UUID siteId) {
        return jdbc.queryForList(
            "SELECT site_id, item_id, sku::text AS sku, lot_number, expiration_date, days_remaining, qty FROM v_expiry_risk WHERE site_id = ? ORDER BY days_remaining", siteId);
    }
}
