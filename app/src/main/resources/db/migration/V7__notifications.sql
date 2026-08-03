-- ============================================================
-- WMS Schema V7 — Bell Notifications
-- In-app notifications replace email for operational alerts.
-- Fan-out happens in a trigger on system_alert, so every alert
-- producer (current and future) reaches the bell automatically.
-- ============================================================

CREATE TABLE user_notification (
    notification_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    alert_id        uuid REFERENCES system_alert(alert_id) ON DELETE CASCADE,
    title           text NOT NULL,
    body            text NOT NULL,
    link            text,                    -- deep link, e.g. /alerts?siteId=...
    read_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notif_user_unread ON user_notification(user_id, created_at DESC)
    WHERE read_at IS NULL;

-- Who gets the bell: users whose effective role at the alert's site
-- (grant at the site, its area, or any ancestor — Corporation admins
-- included) carries DASHBOARD_VIEW. Matches the admin-only dashboard
-- rule: the people who can see the dashboards get the bells.
CREATE OR REPLACE FUNCTION fn_alert_notify() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO user_notification (user_id, alert_id, title, body, link)
    SELECT DISTINCT g.user_id,
           NEW.alert_id,
           replace(initcap(replace(NEW.alert_type, '_', ' ')), 'Fefo', 'FEFO'),
           NEW.message,
           '/alerts?siteId=' || NEW.site_id
    FROM user_org_grant g
    JOIN role_capability rc ON rc.role_id = g.role_id
                           AND rc.capability = 'DASHBOARD_VIEW'
    JOIN app_user u ON u.user_id = g.user_id AND u.active
    WHERE g.org_node_id IN (
        WITH RECURSIVE anc AS (
            SELECT org_node_id, parent_id FROM org_node WHERE org_node_id = NEW.site_id
            UNION ALL
            SELECT o.org_node_id, o.parent_id
            FROM org_node o JOIN anc a ON o.org_node_id = a.parent_id
        )
        SELECT org_node_id FROM anc
        UNION
        SELECT NEW.area_id WHERE NEW.area_id IS NOT NULL
    );
    RETURN NEW;
END $$;

CREATE TRIGGER trg_alert_notify
    AFTER INSERT ON system_alert
    FOR EACH ROW EXECUTE FUNCTION fn_alert_notify();
