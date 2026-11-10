package com.artemis.wms.alerts;

import com.artemis.wms.platform.ApiException;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alert producers write one row; the database trigger fans out to every
 * eligible bell and, for CRITICAL, to the email outbox. Zero wiring for
 * future producers.
 */
@Service
public class AlertService {
    private final JdbcTemplate jdbc;
    public AlertService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID raise(UUID siteId, UUID areaId, String type, String severity, String message, String refKind, UUID refId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO system_alert (alert_id, corporation_id, site_id, area_id, alert_type, severity, message, ref_kind, ref_id)
            SELECT ?, corporation_id, ?, ?, ?, ?::alert_severity, ?, ?, ? FROM org_node WHERE org_node_id = ?""",
            id, siteId, areaId, type, severity, message, refKind, refId, siteId);
        return id;
    }

    /**
     * For producers that run unattended (the replenishment scan): raise only if
     * the same condition isn't already on the board, and don't re-raise one
     * that was acknowledged in the last few hours. Returns null when suppressed.
     */
    public UUID raiseOnce(UUID siteId, UUID areaId, String type, String severity, String message, String refKind, UUID refId) {
        Integer live = jdbc.queryForObject("""
            SELECT count(*) FROM system_alert
            WHERE site_id = ? AND alert_type = ? AND ref_id IS NOT DISTINCT FROM ?
              AND (acknowledged_at IS NULL OR created_at > now() - interval '4 hours')""", Integer.class, siteId, type, refId);
        return live != null && live > 0 ? null : raise(siteId, areaId, type, severity, message, refKind, refId);
    }

    public List<Map<String, Object>> open(UUID siteId) {
        return jdbc.queryForList("""
            SELECT a.alert_id, a.alert_type, a.severity::text AS severity, a.message, a.ref_kind, a.ref_id, ar.code AS area, a.created_at
            FROM system_alert a LEFT JOIN org_node ar ON ar.org_node_id = a.area_id
            WHERE a.site_id = ? AND a.acknowledged_at IS NULL ORDER BY a.created_at DESC""", siteId);
    }

    public void acknowledge(UUID alertId) {
        int n = jdbc.update("UPDATE system_alert SET acknowledged_by = ?, acknowledged_at = now() WHERE alert_id = ? AND acknowledged_at IS NULL", TenantContext.user(), alertId);
        if (n == 0) throw ApiException.conflict("Alert not found or already acknowledged.");
    }

    // ---- bell
    public int unreadCount() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM user_notification WHERE user_id = ? AND read_at IS NULL", Integer.class, TenantContext.user());
        return n == null ? 0 : n;
    }
    public List<Map<String, Object>> notifications(boolean unreadOnly) {
        return jdbc.queryForList("SELECT notification_id, alert_id, title, body, link, read_at, created_at FROM user_notification WHERE user_id = ? AND (NOT ? OR read_at IS NULL) ORDER BY created_at DESC LIMIT 100", TenantContext.user(), unreadOnly);
    }
    public void markRead(UUID id) { jdbc.update("UPDATE user_notification SET read_at = now() WHERE notification_id = ? AND user_id = ? AND read_at IS NULL", id, TenantContext.user()); }
    public void markAllRead()     { jdbc.update("UPDATE user_notification SET read_at = now() WHERE user_id = ? AND read_at IS NULL", TenantContext.user()); }
}
