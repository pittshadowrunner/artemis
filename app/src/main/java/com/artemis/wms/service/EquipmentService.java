package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.service.LocationService.*;

@Service
public class EquipmentService {

    private final JdbcTemplate jdbc;

    public EquipmentService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Register a cart / jack / truck. For carts, containerPositions sets the
     * batch size at wave release; per-position check digits can be supplied
     * or are derived deterministically so voice put-verification always has
     * something to say.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(Map<String, Object> body) {
        UUID siteId = uuid(body.get("siteId"));
        String code = str(body.get("code"));
        Integer positions = intVal(body.get("containerPositions"), "containerPositions");

        Integer exists = jdbc.queryForObject(
            "SELECT count(*) FROM equipment WHERE site_id = ? AND code = ?", Integer.class, siteId, code);
        if (exists != null && exists > 0) throw ApiException.conflict("Equipment code already exists at this site.");

        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO equipment (equipment_id, corporation_id, site_id, code, equipment_type,
                lpn, check_digits, container_positions, max_weight_kg, voice_enabled)
            VALUES (?, ?, ?, ?, ?::equipment_type, ?, ?, ?, ?, COALESCE(?, true))
            """, id, TenantContext.corp(), siteId, code, str(body.get("equipmentType")),
            str(body.get("lpn")), str(body.get("checkDigits")), positions,
            num(body.get("maxWeightKg"), ""), boolVal(body.get("voiceEnabled")));

        List<Map<String, Object>> supplied = (List<Map<String, Object>>) body.get("positions");
        if (positions != null) {
            for (int n = 1; n <= positions; n++) {
                String digits = null;
                if (supplied != null) {
                    for (Map<String, Object> p : supplied)
                        if (Integer.valueOf(n).equals(intVal(p.get("positionNo"), ""))) digits = str(p.get("checkDigits"));
                }
                if (digits == null) digits = String.format("%02d", (n * 23) % 100);
                jdbc.update("""
                    INSERT INTO equipment_position (equipment_id, position_no, check_digits)
                    VALUES (?, ?, ?)
                    """, id, n, digits);
            }
        }
        return Map.of("equipmentId", id, "code", code,
                "positions", positions == null ? 0 : positions);
    }

    public List<Map<String, Object>> list(UUID siteId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT equipment_id, code, equipment_type::text AS equipment_type, lpn, check_digits,
                   container_positions, voice_enabled, active
            FROM equipment WHERE site_id = ? ORDER BY code
            """, siteId);
        for (Map<String, Object> r : rows) {
            r.put("positions", jdbc.queryForList("""
                SELECT position_no, check_digits FROM equipment_position
                WHERE equipment_id = ? ORDER BY position_no
                """, r.get("equipment_id")));
        }
        return rows;
    }
}
