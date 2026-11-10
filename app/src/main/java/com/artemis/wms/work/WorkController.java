package com.artemis.wms.work;

import com.artemis.wms.outbound.SelectionService;
import com.artemis.wms.outbound.WaveService;
import com.artemis.wms.platform.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.artemis.wms.platform.Values.str;
import static com.artemis.wms.platform.Values.upper;

@RestController
@RequestMapping("/api/v1")
public class WorkController {
    private final WorkService work; private final WaveService waves; private final SelectionService selection; private final JdbcTemplate jdbc;
    public WorkController(WorkService work, WaveService waves, SelectionService selection, JdbcTemplate jdbc) { this.work = work; this.waves = waves; this.selection = selection; this.jdbc = jdbc; }

    /** {type?} — the caller asks for their next assignment. */
    @PostMapping("/sites/{siteId}/work/next")
    public Map<String, Object> next(@PathVariable UUID siteId, @RequestBody(required = false) Map<String, Object> b) { return work.next(siteId, b == null ? null : str(b.get("type"))); }

    /** Released zone orders waiting to be built. */
    @GetMapping("/sites/{siteId}/selection/unbuilt")
    public List<Map<String, Object>> unbuilt(@PathVariable UUID siteId, @RequestParam(required = false) String tempZone) { return waves.unbuilt(siteId, upper(tempZone)); }

    /** {tempZone, orderNumbers:[...], equipment?} — build one assignment by hand. */
    @PostMapping("/sites/{siteId}/selection/build") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> build(@PathVariable UUID siteId, @RequestBody Map<String, Object> b) {
        String zone = upper(b.get("tempZone"));
        if (zone == null) throw ApiException.badRequest("tempZone is required.");
        List<UUID> ids = new ArrayList<>();
        if (b.get("orderNumbers") instanceof List<?> numbers)
            for (Object n : numbers) {
                List<UUID> found = jdbc.queryForList("SELECT zone_order_id FROM v_unbuilt_zone_order WHERE site_id = ? AND temp_zone::text = ? AND order_number = ?", UUID.class, siteId, zone, String.valueOf(n).toUpperCase());
                if (found.isEmpty()) throw ApiException.conflict(n + " has no released, unbuilt " + zone + " zone order.");
                ids.add(found.get(0));
            }
        return waves.build(siteId, ids, str(b.get("equipment")));
    }

    /** {equipment} — say which unit a selection assignment is picked to. */
    @PostMapping("/assignments/{id}/equipment")
    public Map<String, Object> equipment(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return selection.setEquipment(id, str(b.get("equipment"))); }
}
