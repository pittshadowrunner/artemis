package com.artemis.wms.work;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.outbound.SelectionService;
import com.artemis.wms.outbound.WaveService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * "Give me work." The operator asks; the system decides, in this order:
 *   1. finish what you already have;
 *   2. otherwise the most urgent thing waiting that you are trained and
 *      permitted to do — either an assignment nobody holds, or released
 *      zone orders nobody has built yet, whichever has the higher priority;
 *   3. released zone orders are built into an assignment on the spot, sized
 *      to the unit the operator has checked out.
 * Nobody dispatches it; the gates are the same two as dispatch.
 */
@Service
public class WorkService {
    private static final List<String> TYPES = List.of("PUTAWAY", "REPLENISHMENT", "SELECTION");

    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final DispatchService dispatch;
    private final WaveService waves;
    private final SelectionService selection;
    public WorkService(JdbcTemplate jdbc, AccessService access, DispatchService dispatch, WaveService waves, SelectionService selection) {
        this.jdbc = jdbc; this.access = access; this.dispatch = dispatch; this.waves = waves; this.selection = selection;
    }

    /** The kinds of work the signed-in user could be handed at this site. */
    public List<String> allowed(UUID siteId) {
        UUID me = TenantContext.user();
        if (me == null || TenantContext.corp() == null) return List.of();
        List<String> trained = jdbc.queryForList("SELECT unnest(trained_workflows) FROM app_user WHERE user_id = ? AND active", String.class, me);
        List<String> out = new ArrayList<>();
        for (String type : TYPES)
            if (trained.contains(DispatchService.workflowOf(type)) && access.has(siteId, DispatchService.capabilityOf(type))) out.add(type);
        return out;
    }

    @Transactional
    public Map<String, Object> next(UUID siteId, String requestedType) {
        UUID me = TenantContext.user();
        if (me == null) throw ApiException.badRequest("Sign in as a person to ask for work.");
        List<String> types = new ArrayList<>(allowed(siteId));
        if (types.isEmpty()) throw ApiException.conflict("You aren't trained for any floor work at this site yet — ask a supervisor to set your allowances.");
        if (requestedType != null && !requestedType.isBlank()) {
            String t = requestedType.trim().toUpperCase();
            if (!TYPES.contains(t)) throw ApiException.badRequest("type must be one of " + String.join(", ", TYPES) + ".");
            if (!types.contains(t)) throw ApiException.conflict("You aren't trained for " + t + " — your allowances cover " + String.join(", ", types) + ".");
            types = List.of(t);
        }
        String csv = String.join(",", types);

        // 1. Finish what you have.
        List<Map<String, Object>> mine = jdbc.queryForList("""
            SELECT assignment_id, assignment_number, assignment_type::text AS type, priority FROM assignment
            WHERE site_id = ? AND assigned_to = ? AND status IN ('ASSIGNED','IN_PROGRESS') AND assignment_type::text = ANY(string_to_array(?, ','))
            ORDER BY (status = 'IN_PROGRESS') DESC, priority DESC, created_at LIMIT 1""", siteId, me, csv);
        if (!mine.isEmpty()) return answer(mine.get(0), "RESUMED", me);

        // 2. The best assignment nobody holds, and the best released-but-unbuilt zone order.
        List<Map<String, Object>> open = jdbc.queryForList("""
            SELECT assignment_id, assignment_number, assignment_type::text AS type, priority FROM assignment
            WHERE site_id = ? AND status = 'OPEN' AND assigned_to IS NULL AND assignment_type::text = ANY(string_to_array(?, ','))
            ORDER BY priority DESC, created_at LIMIT 1 FOR UPDATE SKIP LOCKED""", siteId, csv);
        List<Map<String, Object>> unbuilt = types.contains("SELECTION") ? waves.unbuilt(siteId, null).stream().filter(u -> ((Number) u.get("picks")).intValue() > 0).toList() : List.of();
        int openPriority = open.isEmpty() ? 0 : ((Number) open.get(0).get("priority")).intValue();
        int buildPriority = unbuilt.isEmpty() ? 0 : ((Number) unbuilt.get(0).get("work_priority")).intValue();
        if (open.isEmpty() && unbuilt.isEmpty())
            throw ApiException.notFound("No work waiting for you right now" + (types.size() == 1 ? " in " + types.get(0).toLowerCase() : "") + ".");

        if (buildPriority > openPriority) {
            // 3. Build it for them: same zone and wave as the best order, as many as their unit holds.
            Map<String, Object> top = unbuilt.get(0);
            Map<String, Object> unit = jdbc.queryForList("""
                SELECT e.code, e.container_positions FROM equipment_checkout co JOIN equipment e ON e.equipment_id = co.equipment_id
                WHERE co.user_id = ? AND co.checked_in_at IS NULL AND e.site_id = ? AND e.active ORDER BY co.checked_out_at DESC""", me, siteId)
                .stream().findFirst().orElse(null);
            int size = unit != null && unit.get("container_positions") != null ? (Integer) unit.get("container_positions")
                     : top.get("batch_size") != null ? (Integer) top.get("batch_size") : 6;
            List<UUID> take = unbuilt.stream()
                .filter(u -> top.get("temp_zone").equals(u.get("temp_zone")) && top.get("wave_id").equals(u.get("wave_id")))
                .limit(Math.max(1, size)).map(u -> (UUID) u.get("zone_order_id")).toList();
            Map<String, Object> built = waves.buildUnchecked(siteId, take, unit == null ? null : (String) unit.get("code"));
            dispatch.assign((UUID) built.get("assignmentId"), me, null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assignment_id", built.get("assignmentId")); row.put("assignment_number", built.get("assignmentNumber")); row.put("type", "SELECTION"); row.put("priority", buildPriority);
            Map<String, Object> out = answer(row, "BUILT", me);
            out.put("zoneOrders", take.size());
            return out;
        }
        dispatch.assign((UUID) open.get(0).get("assignment_id"), me, null);
        return answer(open.get(0), "ASSIGNED", me);
    }

    private Map<String, Object> answer(Map<String, Object> a, String how, UUID me) {
        UUID id = (UUID) a.get("assignment_id");
        String adopted = selection.adoptCheckedOutUnit(id, me);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assignmentId", id); out.put("assignmentNumber", a.get("assignment_number")); out.put("type", a.get("type")); out.put("priority", a.get("priority"));
        out.put("how", how);
        out.put("equipment", adopted != null ? adopted : jdbc.queryForList("SELECT e.code FROM assignment a JOIN equipment e ON e.equipment_id = a.equipment_id WHERE a.assignment_id = ?", String.class, id).stream().findFirst().orElse(null));
        return out;
    }
}
