package com.artemis.wms.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Resolves capabilities through effective_role / effective_capabilities in SQL. */
@Service
public class CapabilityService {

    private final JdbcTemplate jdbc;

    public CapabilityService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<String> capabilitiesAt(UUID userId, UUID orgNodeId) {
        return jdbc.queryForList("SELECT capability FROM effective_capabilities(?, ?)",
                String.class, userId, orgNodeId);
    }

    public boolean has(UUID userId, UUID orgNodeId, String capability) {
        return capabilitiesAt(userId, orgNodeId).contains(capability);
    }

    /** Does the user hold this capability at any node they're granted on (or its descendants)? */
    public boolean hasAnywhere(UUID userId, String capability) {
        Integer n = jdbc.queryForObject("""
            SELECT count(*) FROM user_org_grant g
            JOIN role_capability rc ON rc.role_id = g.role_id AND rc.capability = ?
            WHERE g.user_id = ?
            """, Integer.class, capability, userId);
        return n != null && n > 0;
    }

    /** Platform tier: above all tenants. Never derived from grants. */
    public boolean isSysadmin(UUID userId) {
        if (userId == null) return false;
        Boolean b = jdbc.queryForObject(
            "SELECT sysadmin FROM app_user WHERE user_id = ?", Boolean.class, userId);
        return Boolean.TRUE.equals(b);
    }

    public void requireSysadmin(UUID userId) {
        if (!isSysadmin(userId))
            throw new org.springframework.security.access.AccessDeniedException(
                "Platform administration requires sysadmin.");
    }

    public void require(UUID userId, UUID orgNodeId, String capability) {
        boolean ok = orgNodeId != null ? has(userId, orgNodeId, capability) : hasAnywhere(userId, capability);
        if (!ok) throw new org.springframework.security.access.AccessDeniedException(
                "Missing capability " + capability);
    }
}
