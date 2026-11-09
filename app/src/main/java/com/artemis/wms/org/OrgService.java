package com.artemis.wms.org;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.artemis.wms.platform.Values.*;

/**
 * Corporation → District Region → Site Location → Area. Nesting is enforced by
 * the fn_org_nesting trigger, so a wrong parent is a clean 409, never a corrupt
 * tree. Corporations are platform-tier: only a sysadmin can create one.
 */
@Service
public class OrgService {
    public static final List<String> LEVELS = List.of("CORPORATION", "DISTRICT_REGION", "SITE_LOCATION", "AREA");
    private static final String ADDRESS_COLS = "address_line1, address_line2, city, state_province, postal_code, country, timezone";
    private final JdbcTemplate jdbc;
    private final AccessService access;
    public OrgService(JdbcTemplate jdbc, AccessService access) { this.jdbc = jdbc; this.access = access; }

    /** Create a corporation (sysadmin only). Returns existing id when the code already exists. */
    @Transactional
    public UUID createCorporation(String code, String name, Map<String, Object> attrs) {
        access.requireSysadmin();
        code = upper(code);
        if (code == null || name == null) throw ApiException.badRequest("Corporation needs code and name.");
        List<UUID> existing = jdbc.queryForList("SELECT org_node_id FROM org_node WHERE level = 'CORPORATION' AND code = ?", UUID.class, code);
        if (!existing.isEmpty()) return existing.get(0);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO org_node (org_node_id, corporation_id, level, code, name, " + ADDRESS_COLS + ") VALUES (?, ?, 'CORPORATION', ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, 'America/New_York'))",
            id, id, code, name, str(attrs.get("addressLine1")), str(attrs.get("addressLine2")), str(attrs.get("city")),
            str(attrs.get("stateProvince")), str(attrs.get("postalCode")), str(attrs.get("country")), str(attrs.get("timezone")));
        return id;
    }

    /** Create-or-update a child node by (parent, code). */
    @Transactional
    public UUID upsertNode(String level, UUID parentId, String code, String name, Map<String, Object> attrs) {
        level = upper(level); code = upper(code);
        if (level == null || !LEVELS.contains(level) || level.equals("CORPORATION"))
            throw ApiException.badRequest("Level must be one of DISTRICT_REGION, SITE_LOCATION, AREA.");
        if (parentId == null || code == null || name == null) throw ApiException.badRequest(level + " needs parentId, code and name.");
        access.require(parentId, Capabilities.ORG_MANAGE);
        List<UUID> existing = jdbc.queryForList("SELECT org_node_id FROM org_node WHERE parent_id = ? AND code = ?", UUID.class, parentId, code);
        if (!existing.isEmpty()) {
            jdbc.update("UPDATE org_node SET name = ?, address_line1 = COALESCE(?, address_line1), address_line2 = COALESCE(?, address_line2), city = COALESCE(?, city), state_province = COALESCE(?, state_province), postal_code = COALESCE(?, postal_code), country = COALESCE(?, country), timezone = COALESCE(?, timezone), active = true, updated_at = now() WHERE org_node_id = ?",
                name, str(attrs.get("addressLine1")), str(attrs.get("addressLine2")), str(attrs.get("city")), str(attrs.get("stateProvince")),
                str(attrs.get("postalCode")), str(attrs.get("country")), str(attrs.get("timezone")), existing.get(0));
            return existing.get(0);
        }
        UUID id = UUID.randomUUID();
        // corporation_id is overwritten by the nesting trigger from the parent; placeholder satisfies NOT NULL
        jdbc.update("INSERT INTO org_node (org_node_id, corporation_id, parent_id, level, code, name, " + ADDRESS_COLS + ") VALUES (?, (SELECT corporation_id FROM org_node WHERE org_node_id = ?), ?, ?::org_level, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, 'America/New_York'))",
            id, parentId, parentId, level, code, name, str(attrs.get("addressLine1")), str(attrs.get("addressLine2")), str(attrs.get("city")),
            str(attrs.get("stateProvince")), str(attrs.get("postalCode")), str(attrs.get("country")), str(attrs.get("timezone")));
        return id;
    }

    public Map<String, Object> node(UUID id) {
        try {
            return jdbc.queryForMap("SELECT org_node_id, corporation_id, parent_id, level::text AS level, code, name, city, state_province, country, timezone, active FROM org_node WHERE org_node_id = ?", id);
        } catch (Exception e) { throw ApiException.notFound("Org node " + id + " not found."); }
    }

    public Map<String, Object> tree(UUID rootId) {
        Map<String, Object> n = node(rootId);
        List<UUID> children = jdbc.queryForList("SELECT org_node_id FROM org_node WHERE parent_id = ? AND active ORDER BY code", UUID.class, rootId);
        n.put("children", children.stream().map(this::tree).toList());
        return n;
    }

    /** All corporations visible to the caller (sysadmin: every tenant; others: their own). */
    public List<Map<String, Object>> corporations() {
        return jdbc.queryForList("SELECT org_node_id, code, name, active, created_at FROM org_node WHERE level = 'CORPORATION' ORDER BY code");
    }

    public List<Map<String, Object>> sites() {
        return jdbc.queryForList("SELECT s.org_node_id AS site_id, s.code, s.name, s.city, s.state_province, r.code AS region FROM org_node s JOIN org_node r ON r.org_node_id = s.parent_id WHERE s.level = 'SITE_LOCATION' AND s.active ORDER BY s.code");
    }

    /** Resolve a node by code path within the tenant: "PIT1" (site) or "PIT1/FREEZER" (area). */
    public UUID resolve(String path) {
        if (path == null) return null;
        String[] parts = path.toUpperCase().split("/");
        UUID node = null;
        for (String p : parts) {
            List<UUID> hits = node == null
                ? jdbc.queryForList("SELECT org_node_id FROM org_node WHERE code = ? AND corporation_id = ? AND level <> 'CORPORATION' ORDER BY level DESC LIMIT 1", UUID.class, p, TenantContext.corp())
                : jdbc.queryForList("SELECT org_node_id FROM org_node WHERE code = ? AND parent_id = ?", UUID.class, p, node);
            if (hits.isEmpty()) throw ApiException.badRequest("Org node '" + path + "' not found (at '" + p + "').");
            node = hits.get(0);
        }
        return node;
    }

    public UUID siteOf(UUID nodeId) {
        List<UUID> s = jdbc.queryForList("SELECT a.org_node_id FROM org_ancestry(?) a JOIN org_node o ON o.org_node_id = a.org_node_id WHERE o.level = 'SITE_LOCATION'", UUID.class, nodeId);
        if (s.isEmpty()) throw ApiException.badRequest("Node " + nodeId + " is not a site or area.");
        return s.get(0);
    }
}
