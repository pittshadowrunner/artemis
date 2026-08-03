package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrgService {

    private static final List<String> LEVELS =
            List.of("CORPORATION", "DISTRICT_REGION", "SITE_LOCATION", "AREA");

    private final JdbcTemplate jdbc;

    public OrgService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Level nesting enforced: Site only under Region, Area only under Site. */
    @Transactional
    public UUID create(String level, UUID parentId, String code, String name, Map<String, String> address) {
        int idx = LEVELS.indexOf(level);
        if (idx < 0) throw ApiException.badRequest("Unknown org level '" + level + "'.");
        UUID corpId;
        if (idx == 0) {
            if (parentId != null) throw ApiException.badRequest("A Corporation must have no parent.");
            corpId = UUID.randomUUID();
        } else {
            if (parentId == null) throw ApiException.badRequest(level + " requires a parent.");
            Map<String, Object> parent = jdbc.queryForMap(
                "SELECT level::text AS level, corporation_id FROM org_node WHERE org_node_id = ?", parentId);
            String parentLevel = (String) parent.get("level");
            if (!LEVELS.get(idx - 1).equals(parentLevel))
                throw ApiException.badRequest(level + " must be created under a " + LEVELS.get(idx - 1) + ".");
            corpId = (UUID) parent.get("corporation_id");
        }
        UUID id = idx == 0 ? corpId : UUID.randomUUID();
        jdbc.update("""
            INSERT INTO org_node (org_node_id, corporation_id, parent_id, level, code, name,
                                  address_line1, address_line2, city, state_province, postal_code, country)
            VALUES (?, ?, ?, ?::org_level, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, corpId, parentId, level, code, name,
            address.get("addressLine1"), address.get("addressLine2"), address.get("city"),
            address.get("stateProvince"), address.get("postalCode"), address.get("country"));
        return id;
    }

    public Map<String, Object> tree(UUID rootId) {
        Map<String, Object> node = jdbc.queryForMap("""
            SELECT org_node_id, level::text AS level, code, name, active FROM org_node WHERE org_node_id = ?
            """, rootId);
        List<UUID> children = jdbc.queryForList(
            "SELECT org_node_id FROM org_node WHERE parent_id = ? ORDER BY code", UUID.class, rootId);
        node.put("children", children.stream().map(this::tree).toList());
        return node;
    }
}
