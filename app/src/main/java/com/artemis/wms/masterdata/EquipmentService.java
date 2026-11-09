package com.artemis.wms.masterdata;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.platform.BulkResult;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static com.artemis.wms.platform.Values.*;

/** Equipment (carts, powered units) and containers (totes). Custody = checkout/checkin. */
@Service
public class EquipmentService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final TransactionTemplate tx;
    public EquipmentService(JdbcTemplate jdbc, AccessService access, TransactionTemplate tx) { this.jdbc = jdbc; this.access = access; this.tx = tx; }

    public BulkResult bulkEquipment(UUID siteId, List<Map<String, Object>> units) {
        UUID corp = access.requireTenant();
        access.require(siteId, Capabilities.EQUIPMENT_MANAGE);
        BulkResult r = new BulkResult();
        int row = 0;
        for (Map<String, Object> e : units) {
            row++;
            String code = upper(e.get("code"));
            try {
                String type = upper(e.get("type"));
                if (code == null || type == null) throw ApiException.badRequest("code and type are required.");
                boolean created = tx.execute(s -> {
                    Integer n = jdbc.queryForObject("SELECT count(*) FROM equipment WHERE site_id = ? AND code = ?", Integer.class, siteId, code);
                    if (n != null && n > 0) {
                        jdbc.update("UPDATE equipment SET equipment_type = ?::equipment_type, container_positions = COALESCE(?, container_positions), capabilities = COALESCE(?, capabilities), max_weight_kg = COALESCE(?, max_weight_kg), active = true WHERE site_id = ? AND code = ?",
                            type, integer(e.get("positions")), array(e.get("capabilities")), number(e.get("maxWeightKg")), siteId, code);
                        return false;
                    }
                    jdbc.update("INSERT INTO equipment (corporation_id, site_id, code, equipment_type, container_positions, capabilities, max_weight_kg, voice_enabled) VALUES (?, ?, ?, ?::equipment_type, ?, COALESCE(?::text[], '{}'::text[]), ?, COALESCE(?, true))",
                        corp, siteId, code, type, integer(e.get("positions")), array(e.get("capabilities")), number(e.get("maxWeightKg")), bool(e.get("voiceEnabled")));
                    return true;
                });
                if (created) r.created(); else r.updated();
            } catch (ApiException x) { r.error(row, code, x.getMessage()); }
              catch (Exception x)    { r.error(row, code, rootMessage(x)); }
        }
        return r;
    }

    public BulkResult bulkContainers(UUID siteId, List<Map<String, Object>> containers) {
        UUID corp = access.requireTenant();
        access.require(siteId, Capabilities.EQUIPMENT_MANAGE);
        BulkResult r = new BulkResult();
        int row = 0;
        for (Map<String, Object> c : containers) {
            row++;
            String barcode = upper(c.get("barcode"));
            try {
                if (barcode == null) throw ApiException.badRequest("barcode is required.");
                int n = jdbc.update("INSERT INTO container (corporation_id, site_id, barcode, container_type, check_digits, reusable, tare_weight_kg, max_weight_kg) VALUES (?, ?, ?, COALESCE(?::container_type, 'TOTE'), lpad((abs(hashtext(?)) % 100)::text, 2, '0'), COALESCE(?, true), ?, ?) ON CONFLICT (site_id, barcode) DO UPDATE SET container_type = EXCLUDED.container_type, active = true",
                    corp, siteId, barcode, upper(c.get("type")), barcode, bool(c.get("reusable")), number(c.get("tareWeightKg")), number(c.get("maxWeightKg")));
                if (n > 0) r.created();
            } catch (ApiException x) { r.error(row, barcode, x.getMessage()); }
              catch (Exception x)    { r.error(row, barcode, rootMessage(x)); }
        }
        return r;
    }

    public List<Map<String, Object>> list(UUID siteId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT e.equipment_id, e.code, e.equipment_type::text AS type, e.container_positions, e.capabilities, e.check_digits, e.active,
                   u.display_name AS checked_out_to, co.checked_out_at
            FROM equipment e
            LEFT JOIN equipment_checkout co ON co.equipment_id = e.equipment_id AND co.checked_in_at IS NULL
            LEFT JOIN app_user u ON u.user_id = co.user_id
            WHERE e.site_id = ? ORDER BY e.equipment_type, e.code""", siteId);
        rows.forEach(com.artemis.wms.platform.Values::deArray);
        return rows;
    }

    @Transactional
    public void checkout(UUID equipmentId, UUID userId) {
        UUID who = userId == null ? TenantContext.user() : userId;
        try {
            jdbc.update("INSERT INTO equipment_checkout (equipment_id, user_id) VALUES (?, ?)", equipmentId, who);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("Unit is already checked out.");
        }
    }

    @Transactional
    public void checkin(UUID equipmentId) {
        int n = jdbc.update("UPDATE equipment_checkout SET checked_in_at = now() WHERE equipment_id = ? AND checked_in_at IS NULL", equipmentId);
        if (n == 0) throw ApiException.conflict("Unit is not checked out.");
    }
}
