package com.artemis.wms.identity;

import com.artemis.wms.platform.ApiException;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Authorization decisions. Highest-wins role resolution happens in SQL
 * (effective_role / effective_capabilities) so the API, UI and voice agree.
 * Sysadmin is the platform tier: it passes every check, and is the only
 * tier that can create corporations.
 */
@Service
public class AccessService {
    private final JdbcTemplate jdbc;
    public AccessService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<String> capabilitiesAt(UUID userId, UUID orgNodeId) {
        return jdbc.queryForList("SELECT capability FROM effective_capabilities(?, ?)", String.class, userId, orgNodeId);
    }

    public boolean has(UUID orgNodeId, String capability) {
        if (TenantContext.sysadmin()) return true;
        UUID user = TenantContext.user();
        if (user == null) return false;
        if (orgNodeId != null) return capabilitiesAt(user, orgNodeId).contains(capability);
        Integer n = jdbc.queryForObject("""
            SELECT count(*) FROM user_org_grant g
            JOIN role_capability rc ON rc.role_id = g.role_id AND rc.capability = ?
            WHERE g.user_id = ?""", Integer.class, capability, user);
        return n != null && n > 0;
    }

    /** Require a capability at a node (or anywhere in the tenant when node is null). */
    public void require(UUID orgNodeId, String capability) {
        if (!has(orgNodeId, capability))
            throw ApiException.forbidden("Missing capability " + capability + (orgNodeId != null ? " at " + orgNodeId : "") + ".");
    }

    public void requireSysadmin() {
        if (!TenantContext.sysadmin()) throw ApiException.forbidden("Platform administration requires sysadmin.");
    }

    /** Every tenant-scoped write needs a tenant; sysadmins pick one via the X-Artemis-Corporation header. */
    public UUID requireTenant() {
        UUID corp = TenantContext.corp();
        if (corp == null) throw ApiException.badRequest(
            "No tenant in context. Sysadmins must send X-Artemis-Corporation: <corporation id>.");
        return corp;
    }
}
