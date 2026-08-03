-- ============================================================
-- WMS Schema V4 — Federated Identity & Group-Driven Authorization
-- "IdP authenticates, app authorizes."
-- ============================================================

-- ------------------------------------------------------------
-- 1. PER-TENANT IDENTITY PROVIDER REGISTRATION
--    Backs a dynamic ClientRegistrationRepository. Each
--    corporation brings its own Entra/Okta tenant.
-- ------------------------------------------------------------

CREATE TYPE idp_protocol AS ENUM ('OIDC','SAML2','LOCAL');

CREATE TABLE identity_provider (
    idp_id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id      uuid NOT NULL REFERENCES org_node(org_node_id) ON DELETE CASCADE,
    registration_id     text NOT NULL UNIQUE,   -- used in /oauth2/authorization/{registrationId}
    display_name        text NOT NULL,
    protocol            idp_protocol NOT NULL DEFAULT 'OIDC',
    issuer_uri          text,                   -- OIDC discovery
    client_id           text,
    client_secret_ref   text,                   -- secret manager key, NOT the secret
    scopes              text[] DEFAULT ARRAY['openid','profile','email'],
    groups_claim        text DEFAULT 'groups',  -- 'groups' | 'roles' | 'realm_access.roles'
    -- SAML2 seam (unused until saml2Login is enabled)
    saml_metadata_uri   text,
    saml_group_attribute text,
    jit_provisioning    boolean NOT NULL DEFAULT true,
    auto_link_by_email  boolean NOT NULL DEFAULT true,  -- requires verified email claim
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now()
);

-- Home-realm discovery: email domain -> tenant IdP
CREATE TABLE idp_email_domain (
    domain      citext PRIMARY KEY,             -- 'acme.com'
    idp_id      uuid NOT NULL REFERENCES identity_provider(idp_id) ON DELETE CASCADE
);

-- ------------------------------------------------------------
-- 2. EXTERNAL IDENTITY LINKAGE
--    One local user can hold several federated identities.
-- ------------------------------------------------------------

CREATE TABLE federated_identity (
    federated_identity_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    idp_id          uuid NOT NULL REFERENCES identity_provider(idp_id) ON DELETE CASCADE,
    subject         text NOT NULL,              -- 'sub' claim / SAML NameID
    last_login_at   timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (idp_id, subject)
);
CREATE INDEX idx_fed_user ON federated_identity(user_id);

-- Local accounts are now the exception, not the rule
ALTER TABLE app_user
    ADD COLUMN account_source idp_protocol NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN break_glass    boolean NOT NULL DEFAULT false,
    ADD COLUMN last_login_at  timestamptz;
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;  -- SSO users have none

-- ------------------------------------------------------------
-- 3. GROUP -> ROLE -> ORG SCOPE MAPPING
--    This is the whole authorization model: an IdP group grants
--    a role at an org node. Group membership therefore drives
--    the entire tailored experience.
-- ------------------------------------------------------------

CREATE TABLE idp_group_mapping (
    mapping_id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idp_id          uuid NOT NULL REFERENCES identity_provider(idp_id) ON DELETE CASCADE,
    external_group  text NOT NULL,              -- group object ID or name from the IdP
    external_group_label text,                  -- human-readable, for the admin screen
    role_id         uuid NOT NULL REFERENCES role(role_id),
    org_node_id     uuid NOT NULL REFERENCES org_node(org_node_id) ON DELETE CASCADE,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (idp_id, external_group, org_node_id)
);
CREATE INDEX idx_igm_group ON idp_group_mapping(idp_id, external_group);

-- Grants now record where they came from, so IdP-derived grants
-- can be resynced on every login without clobbering manual ones.
ALTER TABLE user_org_grant
    ADD COLUMN source     idp_protocol NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN mapping_id uuid REFERENCES idp_group_mapping(mapping_id) ON DELETE CASCADE;

-- ------------------------------------------------------------
-- 4. CAPABILITIES — the future custom-role portal, seeded now
-- ------------------------------------------------------------

INSERT INTO role_capability (role_id, capability)
SELECT role_id, c FROM role, unnest(ARRAY[
    'ORG_MANAGE','USER_MANAGE','ITEM_MANAGE','LOCATION_MANAGE','CUSTOMER_MANAGE',
    'RECEIVING_EXECUTE','PUTAWAY_EXECUTE','REPLEN_EXECUTE','SELECTION_EXECUTE','SHIPPING_EXECUTE',
    'WAVE_PLAN','INVENTORY_ADJUST','DASHBOARD_VIEW','METRICS_VIEW_ALL','METRICS_VIEW_SELF'
]) AS c WHERE code = 'ADMIN';

INSERT INTO role_capability (role_id, capability)
SELECT role_id, c FROM role, unnest(ARRAY[
    'METRICS_VIEW_SELF'
]) AS c WHERE code = 'READ_ONLY';
-- Note: DASHBOARD_VIEW and METRICS_VIEW_ALL are ADMIN-only.
-- METRICS_VIEW_SELF lets any user see their own productivity.

-- Resolve every capability a user holds at a node (highest-wins
-- role resolution, then that role's capability set).
CREATE OR REPLACE FUNCTION effective_capabilities(p_user uuid, p_node uuid)
RETURNS TABLE (capability text)
LANGUAGE sql STABLE AS $$
    SELECT rc.capability
    FROM effective_role(p_user, p_node) er
    JOIN role_capability rc ON rc.role_id = er.role_id;
$$;
