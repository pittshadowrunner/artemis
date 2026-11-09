package com.artemis.wms.identity;

import com.artemis.wms.platform.ApiException;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.platform.Values.*;

/** Local user accounts, grants, and operator allowances. */
@Service
public class UserService {
    public static final List<String> WORKFLOWS = List.of("RECEIVING","PUTAWAY","REPLENISHMENT","SELECTION","SHIPPING");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AccessService access;

    public UserService(JdbcTemplate jdbc, PasswordEncoder encoder, AccessService access) {
        this.jdbc = jdbc; this.encoder = encoder; this.access = access;
    }

    /** Create-or-update by email. Password is optional (SSO-only accounts have none). */
    @Transactional
    public UUID upsert(UUID corporationId, String email, String displayName, String password, List<String> workflows) {
        access.require(corporationId, Capabilities.USER_MANAGE);
        if (email == null) throw ApiException.badRequest("email is required.");
        if (displayName == null) throw ApiException.badRequest("displayName is required.");
        for (String w : workflows) if (!WORKFLOWS.contains(w)) throw ApiException.badRequest("Unknown workflow '" + w + "'. Valid: " + WORKFLOWS);
        List<UUID> existing = jdbc.queryForList(
            "SELECT user_id FROM app_user WHERE email = ?::citext AND corporation_id = ?", UUID.class, email, corporationId);
        if (!existing.isEmpty()) {
            jdbc.update("""
                UPDATE app_user SET display_name = ?, trained_workflows = ?, active = true, updated_at = now(),
                    password_hash = COALESCE(?, password_hash)
                WHERE user_id = ?""", displayName, workflows.toArray(String[]::new),
                password == null ? null : encoder.encode(password), existing.get(0));
            return existing.get(0);
        }
        Integer clash = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?::citext", Integer.class, email);
        if (clash != null && clash > 0) throw ApiException.conflict("Email " + email + " belongs to another tenant or the platform.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_user (user_id, corporation_id, email, display_name, password_hash, email_verified, trained_workflows)
            VALUES (?, ?, ?, ?, ?, true, ?)""", id, corporationId, email, displayName,
            password == null ? null : encoder.encode(password), workflows.toArray(String[]::new));
        return id;
    }

    /** Grant a role at an org node; idempotent. */
    @Transactional
    public void grant(UUID userId, UUID orgNodeId, String roleCode) {
        access.require(orgNodeId, Capabilities.USER_MANAGE);
        int n = jdbc.update("""
            INSERT INTO user_org_grant (user_id, org_node_id, role_id, granted_by)
            SELECT ?, ?, role_id, ? FROM role WHERE code = ? AND corporation_id IS NULL
            ON CONFLICT (user_id, org_node_id, role_id) DO NOTHING""",
            userId, orgNodeId, TenantContext.user(), roleCode.toUpperCase());
        Integer roleExists = jdbc.queryForObject("SELECT count(*) FROM role WHERE code = ? AND corporation_id IS NULL", Integer.class, roleCode.toUpperCase());
        if (roleExists == null || roleExists == 0) throw ApiException.badRequest("Unknown role '" + roleCode + "'.");
    }

    public UUID idByEmail(String email) {
        List<UUID> ids = jdbc.queryForList("SELECT user_id FROM app_user WHERE email = ?::citext", UUID.class, email);
        if (ids.isEmpty()) throw ApiException.notFound("No user with email " + email + ".");
        return ids.get(0);
    }

    public List<Map<String, Object>> operators(UUID corporationId) {
        List<Map<String, Object>> users = jdbc.queryForList("""
            SELECT u.user_id, u.email::text AS email, u.display_name, u.trained_workflows, u.active, u.last_login_at
            FROM app_user u WHERE u.corporation_id = ? ORDER BY u.display_name""", corporationId);
        for (Map<String, Object> u : users) {
            deArray(u);
            u.put("grants", jdbc.queryForList("""
                SELECT o.code AS node, o.level::text AS level, r.code AS role
                FROM user_org_grant g JOIN org_node o ON o.org_node_id = g.org_node_id JOIN role r ON r.role_id = g.role_id
                WHERE g.user_id = ? ORDER BY r.rank DESC""", u.get("user_id")));
        }
        return users;
    }

    public Map<String, Object> me() {
        TenantContext.Principal p = TenantContext.current();
        if (p == null) throw ApiException.forbidden("Not authenticated.");
        Map<String, Object> me = new java.util.LinkedHashMap<>();
        me.put("userId", p.userId()); me.put("email", p.email());
        me.put("corporationId", p.corporationId()); me.put("sysadmin", p.sysadmin());
        me.put("grants", jdbc.queryForList("""
            SELECT o.org_node_id, o.code, o.level::text AS level, r.code AS role
            FROM user_org_grant g JOIN org_node o ON o.org_node_id = g.org_node_id JOIN role r ON r.role_id = g.role_id
            WHERE g.user_id = ? ORDER BY r.rank DESC, o.code""", p.userId()));
        return me;
    }
}
