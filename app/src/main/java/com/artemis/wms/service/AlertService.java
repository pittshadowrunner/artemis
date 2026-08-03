package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alert producers write one row here; the V7/V8 database trigger fans out to
 * every eligible bell and, for CRITICAL severity, to the email outbox. Zero
 * wiring for future producers.
 */
@Service
public class AlertService {

    private final JdbcTemplate jdbc;

    public AlertService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID raise(UUID siteId, UUID areaId, String type, String severity, String message,
                      UUID orderId, UUID itemId, UUID inventoryId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO system_alert (alert_id, corporation_id, site_id, area_id, alert_type, severity,
                message, order_id, item_id, inventory_id)
            VALUES (?, ?, ?, ?, ?, ?::alert_severity, ?, ?, ?, ?)
            """, id, TenantContext.corp(), siteId, areaId, type, severity, message, orderId, itemId, inventoryId);
        return id;
    }

    public List<Map<String, Object>> open(UUID siteId, UUID areaId) {
        if (areaId != null) {
            return jdbc.queryForList("""
                SELECT alert_id, alert_type, severity::text AS severity, message, created_at
                FROM system_alert WHERE site_id = ? AND area_id = ? AND acknowledged_at IS NULL
                ORDER BY created_at DESC
                """, siteId, areaId);
        }
        return jdbc.queryForList("""
            SELECT alert_id, alert_type, severity::text AS severity, message, created_at
            FROM system_alert WHERE site_id = ? AND acknowledged_at IS NULL
            ORDER BY created_at DESC
            """, siteId);
    }

    public void acknowledge(UUID alertId) {
        int n = jdbc.update("""
            UPDATE system_alert SET acknowledged_by = ?, acknowledged_at = now()
            WHERE alert_id = ? AND acknowledged_at IS NULL
            """, TenantContext.user(), alertId);
        if (n == 0) throw ApiException.conflict("Alert not found or already acknowledged.");
    }
}
