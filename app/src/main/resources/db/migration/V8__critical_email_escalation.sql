-- ============================================================
-- WMS Schema V8 — Social-style alerting with emergency email
-- Everything reaches the bell. CRITICAL alerts ALSO land in an
-- email outbox for emergency escalation. Outbox pattern: alert
-- creation never blocks on (or fails with) the mail provider —
-- a background job drains the queue.
-- ============================================================

CREATE TABLE email_outbox (
    outbox_id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_id        uuid REFERENCES system_alert(alert_id) ON DELETE CASCADE,
    recipient       citext NOT NULL,
    subject         text NOT NULL,
    body            text NOT NULL,
    attempts        int NOT NULL DEFAULT 0,
    last_error      text,
    sent_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pending ON email_outbox(created_at)
    WHERE sent_at IS NULL AND attempts < 5;

-- Extend the alert fan-out: bells for everyone eligible (unchanged),
-- plus outbox rows when severity is CRITICAL.
CREATE OR REPLACE FUNCTION fn_alert_notify() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    WITH eligible AS (
        SELECT DISTINCT g.user_id, u.email, u.display_name
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
        )
    ),
    bells AS (
        INSERT INTO user_notification (user_id, alert_id, title, body, link)
        SELECT user_id, NEW.alert_id,
               replace(initcap(replace(NEW.alert_type, '_', ' ')), 'Fefo', 'FEFO'),
               NEW.message,
               '/alerts?siteId=' || NEW.site_id
        FROM eligible
        RETURNING 1
    )
    INSERT INTO email_outbox (alert_id, recipient, subject, body)
    SELECT NEW.alert_id, email,
           '[CRITICAL] ' || replace(initcap(replace(NEW.alert_type, '_', ' ')), 'Fefo', 'FEFO'),
           NEW.message
    FROM eligible
    WHERE NEW.severity = 'CRITICAL';
    RETURN NEW;
END $$;
