package com.artemis.wms.dataimport;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.platform.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Platform operations (sysadmin). Wipe replaces the old docker-cp'd SQL:
 *   scope=operations  inventory, movements, assignments, manifests, alerts (keeps master data)
 *   scope=all         everything under the corporation, including the corporation itself
 * The confirm token is the corporation code — a typo can't nuke the wrong tenant.
 */
@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    public AdminService(JdbcTemplate jdbc, AccessService access) { this.jdbc = jdbc; this.access = access; }

    @Transactional
    public Map<String, Object> wipe(UUID corp, String scope, String confirm) {
        access.requireSysadmin();
        String code;
        try { code = jdbc.queryForObject("SELECT code FROM org_node WHERE org_node_id = ? AND level = 'CORPORATION'", String.class, corp); }
        catch (Exception e) { throw ApiException.notFound("Corporation not found."); }
        if (!code.equalsIgnoreCase(confirm)) throw ApiException.badRequest("confirm must equal the corporation code (" + code + ").");
        boolean all = "all".equalsIgnoreCase(scope);
        int alerts = jdbc.update("DELETE FROM system_alert WHERE corporation_id = ?", corp);
        int assignments = jdbc.update("DELETE FROM assignment WHERE corporation_id = ?", corp);
        int pallets = jdbc.update("DELETE FROM inventory WHERE corporation_id = ?", corp);
        int manifests = jdbc.update("DELETE FROM receiving_manifest WHERE corporation_id = ?", corp);
        jdbc.update("DELETE FROM equipment_checkout WHERE equipment_id IN (SELECT equipment_id FROM equipment WHERE corporation_id = ?)", corp);
        Map<String, Object> out = new java.util.LinkedHashMap<>(Map.of("corporation", code, "scope", all ? "all" : "operations",
            "alerts", alerts, "assignments", assignments, "pallets", pallets, "manifests", manifests));
        if (all) {
            out.put("locations", jdbc.update("DELETE FROM location WHERE corporation_id = ?", corp));
            out.put("equipment", jdbc.update("DELETE FROM equipment WHERE corporation_id = ?", corp));
            out.put("containers", jdbc.update("DELETE FROM container WHERE corporation_id = ?", corp));
            out.put("items", jdbc.update("DELETE FROM item WHERE corporation_id = ?", corp));
            out.put("customers", jdbc.update("DELETE FROM customer WHERE corporation_id = ?", corp));
            out.put("users", jdbc.update("DELETE FROM app_user WHERE corporation_id = ?", corp));
            jdbc.update("DELETE FROM inventory_policy WHERE corporation_id = ?", corp);
            // children first: areas → sites → regions → corporation
            out.put("orgNodes", jdbc.update("DELETE FROM org_node WHERE corporation_id = ? AND level = 'AREA'", corp)
                + jdbc.update("DELETE FROM org_node WHERE corporation_id = ? AND level = 'SITE_LOCATION'", corp)
                + jdbc.update("DELETE FROM org_node WHERE corporation_id = ? AND level = 'DISTRICT_REGION'", corp)
                + jdbc.update("DELETE FROM org_node WHERE org_node_id = ?", corp));
        }
        return out;
    }
}
