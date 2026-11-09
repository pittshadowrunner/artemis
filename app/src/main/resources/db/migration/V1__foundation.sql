-- ============================================================
-- Artemis WMS — V1 Foundation (PostgreSQL 16+)
--
-- Org hierarchy, identity, RBAC, inventory-control policies,
-- alerts/notifications/outbox, and tenant Row Level Security.
--
-- Tenancy model
--   • Every tenant-scoped row carries corporation_id.
--   • The app sets app.current_corp per connection; RLS is FORCED so
--     the policies apply to the table owner (the app role) too.
--   • Platform sysadmins set app.platform_admin = 'on' and see every
--     tenant. Sysadmin is a flag on the user, never a grant.
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive emails / SKUs

-- ------------------------------------------------------------
-- Tenant isolation helper: one call per tenant table.
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION tenant_isolate(p_table text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', p_table);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', p_table);
    EXECUTE format($p$
        CREATE POLICY tenant_iso ON %I
        USING (corporation_id = NULLIF(current_setting('app.current_corp', true), '')::uuid
               OR current_setting('app.platform_admin', true) = 'on')
        WITH CHECK (corporation_id = NULLIF(current_setting('app.current_corp', true), '')::uuid
               OR current_setting('app.platform_admin', true) = 'on')$p$, p_table);
END $$;

-- ------------------------------------------------------------
-- 1. ORGANIZATIONAL HIERARCHY
--    Corporation → District Region → Site Location → Area
-- ------------------------------------------------------------
CREATE TYPE org_level AS ENUM ('CORPORATION','DISTRICT_REGION','SITE_LOCATION','AREA');

CREATE TABLE org_node (
    org_node_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,                     -- = org_node_id for CORPORATION rows
    parent_id       uuid REFERENCES org_node(org_node_id),
    level           org_level NOT NULL,
    code            text NOT NULL,
    name            text NOT NULL,
    address_line1   text,
    address_line2   text,
    city            text,
    state_province  text,
    postal_code     text,
    country         text,
    timezone        text NOT NULL DEFAULT 'America/New_York',
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (parent_id, code),
    CONSTRAINT chk_org_root CHECK (
        (level = 'CORPORATION' AND parent_id IS NULL AND corporation_id = org_node_id)
        OR (level <> 'CORPORATION' AND parent_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_org_corp_code ON org_node(code) WHERE level = 'CORPORATION';
CREATE INDEX idx_org_node_corp   ON org_node(corporation_id);
CREATE INDEX idx_org_node_parent ON org_node(parent_id);

-- Nesting is a schema guarantee, not a service-layer convention.
CREATE OR REPLACE FUNCTION fn_org_nesting() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE parent_level org_level; parent_corp uuid;
BEGIN
    IF NEW.level = 'CORPORATION' THEN RETURN NEW; END IF;
    SELECT level, corporation_id INTO parent_level, parent_corp
    FROM org_node WHERE org_node_id = NEW.parent_id;
    IF parent_level IS NULL THEN
        RAISE EXCEPTION 'Parent org node % not found', NEW.parent_id USING ERRCODE = '23503';
    END IF;
    IF NOT ((NEW.level = 'DISTRICT_REGION' AND parent_level = 'CORPORATION')
         OR (NEW.level = 'SITE_LOCATION'   AND parent_level = 'DISTRICT_REGION')
         OR (NEW.level = 'AREA'            AND parent_level = 'SITE_LOCATION')) THEN
        RAISE EXCEPTION '% must be created under a %, not a %',
            NEW.level,
            CASE NEW.level WHEN 'DISTRICT_REGION' THEN 'CORPORATION'
                           WHEN 'SITE_LOCATION' THEN 'DISTRICT_REGION' ELSE 'SITE_LOCATION' END,
            parent_level USING ERRCODE = '23514';
    END IF;
    NEW.corporation_id := parent_corp;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_org_nesting BEFORE INSERT OR UPDATE OF parent_id, level ON org_node
    FOR EACH ROW EXECUTE FUNCTION fn_org_nesting();

-- Ancestor walk used by RBAC and policy resolution.
CREATE OR REPLACE FUNCTION org_ancestry(p_node uuid)
RETURNS TABLE (org_node_id uuid, depth int)
LANGUAGE sql STABLE AS $$
    WITH RECURSIVE a AS (
        SELECT org_node_id, parent_id, 0 AS depth FROM org_node WHERE org_node_id = p_node
        UNION ALL
        SELECT o.org_node_id, o.parent_id, a.depth + 1
        FROM org_node o JOIN a ON o.org_node_id = a.parent_id)
    SELECT org_node_id, depth FROM a;
$$;

-- ------------------------------------------------------------
-- 2. USERS & IDENTITY  ("IdP authenticates, app authorizes")
-- ------------------------------------------------------------
CREATE TYPE idp_protocol AS ENUM ('OIDC','SAML2','LOCAL');

CREATE TABLE app_user (
    user_id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id    uuid REFERENCES org_node(org_node_id) ON DELETE CASCADE,  -- NULL = platform user
    email             citext NOT NULL UNIQUE,
    display_name      text NOT NULL,
    password_hash     text,                             -- NULL for SSO-only accounts
    account_source    idp_protocol NOT NULL DEFAULT 'LOCAL',
    email_verified    boolean NOT NULL DEFAULT false,
    sysadmin          boolean NOT NULL DEFAULT false,   -- platform tier, never a grant
    break_glass       boolean NOT NULL DEFAULT false,
    trained_workflows text[] NOT NULL DEFAULT '{}',     -- operator allowances
    active            boolean NOT NULL DEFAULT true,
    last_login_at     timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_sysadmin_platform CHECK (NOT sysadmin OR corporation_id IS NULL),
    CONSTRAINT chk_workflows CHECK (trained_workflows <@ ARRAY['RECEIVING','PUTAWAY','REPLENISHMENT','SELECTION','SHIPPING'])
);
CREATE INDEX idx_user_corp ON app_user(corporation_id);

CREATE TABLE email_verification_token (
    token_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    token_hash  text NOT NULL,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX idx_evt_user ON email_verification_token(user_id);

-- Per-tenant identity provider registry (backs a dynamic client registration repository).
CREATE TABLE identity_provider (
    idp_id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id       uuid NOT NULL REFERENCES org_node(org_node_id) ON DELETE CASCADE,
    registration_id      text NOT NULL UNIQUE,          -- /oauth2/authorization/{registration_id}
    display_name         text NOT NULL,
    protocol             idp_protocol NOT NULL DEFAULT 'OIDC',
    issuer_uri           text,
    client_id            text,
    client_secret_ref    text,                          -- secret-manager key, never the secret
    scopes               text[] NOT NULL DEFAULT ARRAY['openid','profile','email'],
    groups_claim         text NOT NULL DEFAULT 'groups',
    saml_metadata_uri    text,                          -- SAML2 seam
    saml_group_attribute text,
    jit_provisioning     boolean NOT NULL DEFAULT true,
    auto_link_by_email   boolean NOT NULL DEFAULT true, -- requires a verified email claim
    active               boolean NOT NULL DEFAULT true,
    created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE idp_email_domain (            -- home-realm discovery: email domain → IdP
    domain  citext PRIMARY KEY,
    idp_id  uuid NOT NULL REFERENCES identity_provider(idp_id) ON DELETE CASCADE
);

CREATE TABLE federated_identity (          -- one user may hold several external identities
    federated_identity_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    idp_id        uuid NOT NULL REFERENCES identity_provider(idp_id) ON DELETE CASCADE,
    subject       text NOT NULL,
    last_login_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (idp_id, subject)
);
CREATE INDEX idx_fed_user ON federated_identity(user_id);

-- ------------------------------------------------------------
-- 3. RBAC — roles are data; grants attach a role to a user AT an
--    org node; effective access = highest rank across ancestry.
-- ------------------------------------------------------------
CREATE TABLE role (
    role_id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id uuid REFERENCES org_node(org_node_id) ON DELETE CASCADE,  -- NULL = system role
    code           text NOT NULL,
    name           text NOT NULL,
    rank           int  NOT NULL,                       -- higher wins
    UNIQUE NULLS NOT DISTINCT (corporation_id, code)
);

CREATE TABLE role_capability (
    role_id    uuid NOT NULL REFERENCES role(role_id) ON DELETE CASCADE,
    capability text NOT NULL,
    PRIMARY KEY (role_id, capability)
);

INSERT INTO role (code, name, rank) VALUES
    ('ADMIN',     'Administrator', 100),
    ('SUPERVISOR','Supervisor',     50),
    ('OPERATOR',  'Operator',       20),
    ('READ_ONLY', 'Read Only',      10);

INSERT INTO role_capability (role_id, capability)
SELECT role_id, c FROM role, unnest(ARRAY[
    'ORG_MANAGE','USER_MANAGE','ITEM_MANAGE','LOCATION_MANAGE','CUSTOMER_MANAGE','EQUIPMENT_MANAGE',
    'RECEIVING_EXECUTE','PUTAWAY_EXECUTE','REPLEN_EXECUTE','SELECTION_EXECUTE','SHIPPING_EXECUTE',
    'WAVE_PLAN','INVENTORY_ADJUST','DASHBOARD_VIEW','METRICS_VIEW_ALL','METRICS_VIEW_SELF','DATA_IMPORT'
]) AS c WHERE code = 'ADMIN';
INSERT INTO role_capability (role_id, capability)
SELECT role_id, c FROM role, unnest(ARRAY[
    'RECEIVING_EXECUTE','PUTAWAY_EXECUTE','REPLEN_EXECUTE','SELECTION_EXECUTE','SHIPPING_EXECUTE',
    'WAVE_PLAN','INVENTORY_ADJUST','DASHBOARD_VIEW','METRICS_VIEW_ALL','METRICS_VIEW_SELF'
]) AS c WHERE code = 'SUPERVISOR';
INSERT INTO role_capability (role_id, capability)
SELECT role_id, c FROM role, unnest(ARRAY[
    'RECEIVING_EXECUTE','PUTAWAY_EXECUTE','REPLEN_EXECUTE','SELECTION_EXECUTE','SHIPPING_EXECUTE','METRICS_VIEW_SELF'
]) AS c WHERE code = 'OPERATOR';
INSERT INTO role_capability (role_id, capability)
SELECT role_id, 'METRICS_VIEW_SELF' FROM role WHERE code = 'READ_ONLY';

-- IdP group → role @ org node. Group membership drives the whole tailored experience.
CREATE TABLE idp_group_mapping (
    mapping_id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idp_id               uuid NOT NULL REFERENCES identity_provider(idp_id) ON DELETE CASCADE,
    external_group       text NOT NULL,
    external_group_label text,
    role_id              uuid NOT NULL REFERENCES role(role_id),
    org_node_id          uuid NOT NULL REFERENCES org_node(org_node_id) ON DELETE CASCADE,
    created_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (idp_id, external_group, org_node_id)
);
CREATE INDEX idx_igm_group ON idp_group_mapping(idp_id, external_group);

CREATE TABLE user_org_grant (
    grant_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    org_node_id uuid NOT NULL REFERENCES org_node(org_node_id) ON DELETE CASCADE,
    role_id     uuid NOT NULL REFERENCES role(role_id),
    source      idp_protocol NOT NULL DEFAULT 'LOCAL',  -- IdP-derived grants resync on login
    mapping_id  uuid REFERENCES idp_group_mapping(mapping_id) ON DELETE CASCADE,
    granted_by  uuid REFERENCES app_user(user_id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, org_node_id, role_id)
);
CREATE INDEX idx_grant_user ON user_org_grant(user_id);
CREATE INDEX idx_grant_node ON user_org_grant(org_node_id);

CREATE OR REPLACE FUNCTION effective_role(p_user uuid, p_node uuid)
RETURNS TABLE (role_id uuid, code text, rank int)
LANGUAGE sql STABLE AS $$
    SELECT r.role_id, r.code, r.rank
    FROM user_org_grant g
    JOIN role r ON r.role_id = g.role_id
    WHERE g.user_id = p_user
      AND g.org_node_id IN (SELECT org_node_id FROM org_ancestry(p_node))
    ORDER BY r.rank DESC
    LIMIT 1;
$$;

CREATE OR REPLACE FUNCTION effective_capabilities(p_user uuid, p_node uuid)
RETURNS TABLE (capability text)
LANGUAGE sql STABLE AS $$
    SELECT rc.capability
    FROM effective_role(p_user, p_node) er
    JOIN role_capability rc ON rc.role_id = er.role_id;
$$;

-- ------------------------------------------------------------
-- 4. INVENTORY CONTROL POLICY — set at Site or Area; nearest
--    (smallest) unit wins, field by field.
-- ------------------------------------------------------------
CREATE TYPE rotation_strategy AS ENUM ('FEFO','FIFO','LIFO','NONE');

CREATE TABLE inventory_policy (
    policy_id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id              uuid NOT NULL,
    org_node_id                 uuid NOT NULL UNIQUE REFERENCES org_node(org_node_id) ON DELETE CASCADE,
    rotation                    rotation_strategy,
    min_shelf_life_receipt_days int,
    min_shelf_life_ship_days    int,
    expiry_alert_days           int,
    allow_lot_mixing            boolean,
    allow_item_mixing           boolean,
    catch_weight_tolerance_pct  numeric(5,2),
    require_lot_capture         boolean,
    require_expiry_capture      boolean,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION effective_policy(p_node uuid)
RETURNS inventory_policy
LANGUAGE sql STABLE AS $$
    WITH chain AS (
        SELECT p.*, a.depth
        FROM org_ancestry(p_node) a
        JOIN inventory_policy p ON p.org_node_id = a.org_node_id)
    SELECT
        gen_random_uuid(),
        (SELECT corporation_id FROM org_node WHERE org_node_id = p_node),
        p_node,
        (array_remove(array_agg(rotation ORDER BY depth), NULL))[1],
        (array_remove(array_agg(min_shelf_life_receipt_days ORDER BY depth), NULL))[1],
        (array_remove(array_agg(min_shelf_life_ship_days ORDER BY depth), NULL))[1],
        (array_remove(array_agg(expiry_alert_days ORDER BY depth), NULL))[1],
        (array_remove(array_agg(allow_lot_mixing ORDER BY depth), NULL))[1],
        (array_remove(array_agg(allow_item_mixing ORDER BY depth), NULL))[1],
        (array_remove(array_agg(catch_weight_tolerance_pct ORDER BY depth), NULL))[1],
        (array_remove(array_agg(require_lot_capture ORDER BY depth), NULL))[1],
        (array_remove(array_agg(require_expiry_capture ORDER BY depth), NULL))[1],
        now(), now()
    FROM chain;
$$;

-- ------------------------------------------------------------
-- 5. ALERTS → BELLS → (CRITICAL only) EMAIL OUTBOX
--    Fan-out is a trigger, so every producer reaches the bell
--    with zero wiring. Mail never blocks warehouse operations.
-- ------------------------------------------------------------
CREATE TYPE alert_severity AS ENUM ('INFO','WARNING','CRITICAL');

CREATE TABLE system_alert (
    alert_id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    area_id         uuid REFERENCES org_node(org_node_id),
    alert_type      text NOT NULL,             -- ROTATION_BYPASS, REPLEN_CRITICAL, AGING_STOCK ...
    severity        alert_severity NOT NULL DEFAULT 'INFO',
    message         text NOT NULL,
    ref_kind        text,                      -- ORDER | ITEM | INVENTORY | MANIFEST | ASSIGNMENT
    ref_id          uuid,
    acknowledged_by uuid REFERENCES app_user(user_id),
    acknowledged_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_alert_site_open ON system_alert(site_id, created_at DESC) WHERE acknowledged_at IS NULL;

CREATE TABLE user_notification (
    notification_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    alert_id        uuid REFERENCES system_alert(alert_id) ON DELETE CASCADE,
    title           text NOT NULL,
    body            text NOT NULL,
    link            text,
    read_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notif_user_unread ON user_notification(user_id, created_at DESC) WHERE read_at IS NULL;

CREATE TABLE email_outbox (
    outbox_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_id   uuid REFERENCES system_alert(alert_id) ON DELETE CASCADE,
    recipient  citext NOT NULL,
    subject    text NOT NULL,
    body       text NOT NULL,
    attempts   int NOT NULL DEFAULT 0,
    last_error text,
    sent_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pending ON email_outbox(created_at) WHERE sent_at IS NULL AND attempts < 5;

CREATE OR REPLACE FUNCTION alert_title(p_type text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT replace(replace(initcap(replace(p_type, '_', ' ')), 'Fefo', 'FEFO'), 'Lpn', 'LPN');
$$;

-- Who gets the bell: everyone whose effective role at the alert's site
-- (or area, or any ancestor) carries DASHBOARD_VIEW.
CREATE OR REPLACE FUNCTION fn_alert_notify() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    WITH eligible AS (
        SELECT DISTINCT g.user_id, u.email
        FROM user_org_grant g
        JOIN role_capability rc ON rc.role_id = g.role_id AND rc.capability = 'DASHBOARD_VIEW'
        JOIN app_user u ON u.user_id = g.user_id AND u.active
        WHERE g.org_node_id IN (
            SELECT org_node_id FROM org_ancestry(NEW.site_id)
            UNION SELECT NEW.area_id WHERE NEW.area_id IS NOT NULL)
    ),
    bells AS (
        INSERT INTO user_notification (user_id, alert_id, title, body, link)
        SELECT user_id, NEW.alert_id, alert_title(NEW.alert_type), NEW.message,
               '/alerts?siteId=' || NEW.site_id
        FROM eligible RETURNING 1
    )
    INSERT INTO email_outbox (alert_id, recipient, subject, body)
    SELECT NEW.alert_id, email, '[CRITICAL] ' || alert_title(NEW.alert_type), NEW.message
    FROM eligible WHERE NEW.severity = 'CRITICAL';
    RETURN NEW;
END $$;
CREATE TRIGGER trg_alert_notify AFTER INSERT ON system_alert
    FOR EACH ROW EXECUTE FUNCTION fn_alert_notify();

-- ------------------------------------------------------------
-- 6. ROW LEVEL SECURITY
-- ------------------------------------------------------------
SELECT tenant_isolate(t) FROM unnest(ARRAY['org_node','inventory_policy','system_alert']) AS t;
