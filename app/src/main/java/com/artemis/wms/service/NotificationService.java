package com.artemis.wms.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bell endpoints backing store. All queries scoped to one user — no cross-user read. */
@Service
public class NotificationService {

    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long unreadCount(UUID userId) {
        Long n = jdbc.queryForObject(
            "SELECT count(*) FROM user_notification WHERE user_id = ? AND read_at IS NULL", Long.class, userId);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> list(UUID userId, boolean unreadOnly, int limit) {
        String where = unreadOnly ? " AND read_at IS NULL " : " ";
        return jdbc.queryForList("""
            SELECT notification_id, alert_id, title, body, link, read_at, created_at
            FROM user_notification WHERE user_id = ? """ + where + """
            ORDER BY created_at DESC LIMIT ?
            """, userId, limit);
    }

    public void markRead(UUID userId, UUID notificationId) {
        jdbc.update("""
            UPDATE user_notification SET read_at = now()
            WHERE user_id = ? AND notification_id = ? AND read_at IS NULL
            """, userId, notificationId);
    }

    public int markAllRead(UUID userId) {
        return jdbc.update(
            "UPDATE user_notification SET read_at = now() WHERE user_id = ? AND read_at IS NULL", userId);
    }
}
