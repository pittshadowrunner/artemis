package com.artemis.wms.masterdata;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.org.OrgService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.platform.BulkResult;
import com.artemis.wms.platform.RowSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

import static com.artemis.wms.platform.Values.*;

/**
 * Locations. Codes follow Zone-Aisle-Level-Column-Slot; sequence and check
 * digits derive in the database. Bulk loads upsert by (site, code).
 * {@link #generate} enumerates a rack from its dimensions.
 */
@Service
public class LocationService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final OrgService org;
    private final ItemService items;
    private final TransactionTemplate tx;

    public LocationService(JdbcTemplate jdbc, AccessService access, OrgService org, ItemService items, TransactionTemplate tx) {
        this.jdbc = jdbc; this.access = access; this.org = org; this.items = items; this.tx = tx;
    }

    public BulkResult bulk(UUID siteId, List<Map<String, Object>> locations) {
        UUID corp = access.requireTenant();
        access.require(siteId, Capabilities.LOCATION_MANAGE);
        BulkResult result = new BulkResult();
        int row = 0;
        for (Map<String, Object> loc : locations) {
            row++;
            String code = upper(loc.get("code"));
            try {
                if (code == null) throw ApiException.badRequest("code is required.");
                boolean created = tx.execute(s -> upsert(corp, siteId, code, loc));
                if (created) result.created(); else result.updated();
            } catch (ApiException e) { result.error(row, code, e.getMessage()); }
              catch (Exception e)    { result.error(row, code, rootMessage(e)); }
        }
        return result;
    }

    private boolean upsert(UUID corp, UUID siteId, String code, Map<String, Object> loc) {
        UUID areaId = loc.get("areaId") != null ? uuid(loc.get("areaId"))
                    : loc.get("area") != null ? org.resolve(siteCode(siteId) + "/" + str(loc.get("area"))) : null;
        UUID replenItem = loc.get("replenSku") == null ? null : items.idBySku(corp, str(loc.get("replenSku")));
        Object[] args = { areaId, upper(loc.get("locType")), upper(loc.get("tempZone")), upper(loc.get("rackType")),
            number(loc.get("widthCm")), number(loc.get("depthCm")), number(loc.get("heightCm")), number(loc.get("maxWeightKg")),
            upper(loc.get("velocityZone")), bool(loc.get("goldenZone")), str(loc.get("equipmentClass")),
            bool(loc.get("humidityControlled")), bool(loc.get("hazmatApproved")),
            replenItem, number(loc.get("replenMinQty")), number(loc.get("replenMaxQty")), number(loc.get("replenTriggerQty")) };
        List<UUID> existing = jdbc.queryForList("SELECT location_id FROM location WHERE site_id = ? AND code = ?", UUID.class, siteId, code);
        if (!existing.isEmpty()) {
            List<Object> upd = new ArrayList<>(Arrays.asList(args)); upd.add(existing.get(0));
            jdbc.update("""
                UPDATE location SET area_id = COALESCE(?, area_id), loc_type = COALESCE(?::location_type, loc_type), temp_zone = COALESCE(?::temp_zone, temp_zone),
                    rack_type = COALESCE(?::rack_type, rack_type), width_cm = COALESCE(?, width_cm), depth_cm = COALESCE(?, depth_cm), height_cm = COALESCE(?, height_cm), max_weight_kg = COALESCE(?, max_weight_kg),
                    velocity_zone = COALESCE(?, velocity_zone), golden_zone = COALESCE(?, golden_zone), equipment_class = COALESCE(?, equipment_class),
                    humidity_controlled = COALESCE(?, humidity_controlled), hazmat_approved = COALESCE(?, hazmat_approved),
                    replen_item_id = COALESCE(?, replen_item_id), replen_min_qty = COALESCE(?, replen_min_qty), replen_max_qty = COALESCE(?, replen_max_qty), replen_trigger_qty = COALESCE(?, replen_trigger_qty),
                    active = true, updated_at = now()
                WHERE location_id = ?""", upd.toArray());
            return false;
        }
        List<Object> ins = new ArrayList<>(List.of(corp, siteId, code)); ins.addAll(Arrays.asList(args));
        jdbc.update("""
            INSERT INTO location (corporation_id, site_id, code, area_id, loc_type, temp_zone, rack_type, width_cm, depth_cm, height_cm, max_weight_kg,
                velocity_zone, golden_zone, equipment_class, humidity_controlled, hazmat_approved,
                replen_item_id, replen_min_qty, replen_max_qty, replen_trigger_qty)
            VALUES (?, ?, ?, ?, COALESCE(?::location_type, 'STANDARD'), COALESCE(?::temp_zone, 'AMBIENT'), ?::rack_type, ?, ?, ?, ?,
                ?, COALESCE(?, false), ?, COALESCE(?, false), COALESCE(?, false), ?, ?, ?, ?)""", ins.toArray());
        return true;
    }

    /**
     * Range generation: describe the rack, we enumerate it. Names encode the
     * pick path, so no explicit sequence is needed — the trigger derives it.
     * spec: {zone, aisles:[A,B], levels:[1,2,3], columns:[1..12], slots:[A,B], tempZone, rackType, area}
     */
    public BulkResult generate(UUID siteId, Map<String, Object> spec) {
        String zone = upper(spec.get("zone"));
        if (zone == null) throw ApiException.badRequest("zone is required (first token of the location name).");
        List<String> aisles = strings(spec.get("aisles")), levels = expand(spec.get("levels")), cols = expand(spec.get("columns")), slots = strings(spec.get("slots"));
        if (aisles.isEmpty() || levels.isEmpty() || cols.isEmpty() || slots.isEmpty())
            throw ApiException.badRequest("aisles, levels, columns and slots are all required.");
        List<Map<String, Object>> locs = new ArrayList<>();
        for (String a : aisles) for (String c : cols) for (String s : slots) for (String l : levels) {
            Map<String, Object> loc = new HashMap<>(spec);
            loc.put("code", zone + "-" + a + "-" + l + "-" + pad(c) + "-" + s);
            loc.put("locType", "STANDARD");
            locs.add(loc);
        }
        return bulk(siteId, locs);
    }

    /** "1-12" → 1..12; "1,2,3" → list; lists pass through. */
    static List<String> expand(Object o) {
        if (o instanceof List<?>) return strings(o);
        String s = str(o);
        if (s == null) return List.of();
        if (s.matches("\\d+-\\d+")) {
            String[] p = s.split("-");
            List<String> out = new ArrayList<>();
            for (int i = Integer.parseInt(p[0]); i <= Integer.parseInt(p[1]); i++) out.add(String.valueOf(i));
            return out;
        }
        return strings(s);
    }
    private static String pad(String col) { return col.matches("\\d+") ? String.format("%02d", Integer.parseInt(col)) : col; }

    public BulkResult upload(UUID siteId, MultipartFile file) {
        return bulk(siteId, RowSource.read(file).stream().map(LocationService::fromRow).toList());
    }

    static Map<String, Object> fromRow(Map<String, Object> r) {
        Map<String, Object> loc = new HashMap<>();
        String[][] cols = {{"code","code"},{"loctype","locType"},{"area","area"},{"tempzone","tempZone"},{"racktype","rackType"},{"velocityzone","velocityZone"},
            {"goldenzone","goldenZone"},{"hazmatapproved","hazmatApproved"},{"replensku","replenSku"},{"replenminqty","replenMinQty"},{"replenmaxqty","replenMaxQty"},
            {"replentriggerqty","replenTriggerQty"},{"widthcm","widthCm"},{"depthcm","depthCm"},{"heightcm","heightCm"},{"maxweightkg","maxWeightKg"},{"equipmentclass","equipmentClass"}};
        for (String[] c : cols) if (r.get(c[0]) != null) loc.put(c[1], r.get(c[0]));
        return loc;
    }

    public List<Map<String, Object>> list(UUID siteId, String locType, String q) {
        return jdbc.queryForList("""
            SELECT l.location_id, l.code, l.loc_type::text AS loc_type, l.temp_zone::text AS temp_zone, tag_of(l.temp_zone::text) AS zone_tag,
                   l.zone, l.aisle, l.level, l.col, l.slot, l.pick_sequence, l.check_digits, l.rack_type::text AS rack_type,
                   a.code AS area, i.sku::text AS replen_sku, l.replen_trigger_qty, l.replen_max_qty, l.active,
                   (SELECT count(*) FROM inventory v WHERE v.location_id = l.location_id AND v.status IN ('AVAILABLE','ALLOCATED')) AS pallets
            FROM location l LEFT JOIN org_node a ON a.org_node_id = l.area_id LEFT JOIN item i ON i.item_id = l.replen_item_id
            WHERE l.site_id = ? AND (?::text IS NULL OR l.loc_type::text = ?) AND (?::text IS NULL OR l.code ILIKE '%' || ? || '%')
            ORDER BY l.loc_type, l.pick_sequence NULLS LAST, l.code""", siteId, locType, locType, q, q);
    }

    public Map<String, Object> get(UUID siteId, String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT l.*, l.loc_type::text AS loc_type, l.temp_zone::text AS temp_zone, a.code AS area, i.sku::text AS replen_sku FROM location l LEFT JOIN org_node a ON a.org_node_id = l.area_id LEFT JOIN item i ON i.item_id = l.replen_item_id WHERE l.site_id = ? AND l.code = ?", siteId, code.toUpperCase());
        if (rows.isEmpty()) throw ApiException.notFound("Location " + code + " not found.");
        Map<String, Object> loc = new LinkedHashMap<>(rows.get(0));
        loc.put("contents", jdbc.queryForList("SELECT v.lpn, i.sku::text AS sku, v.qty, v.lot_number, v.expiration_date, v.status::text AS status FROM inventory v JOIN item i ON i.item_id = v.item_id WHERE v.location_id = ? ORDER BY v.expiration_date NULLS LAST, v.arrival_date", loc.get("location_id")));
        return loc;
    }

    public UUID idByCode(UUID siteId, String code) {
        List<UUID> ids = jdbc.queryForList("SELECT location_id FROM location WHERE site_id = ? AND code = ?", UUID.class, siteId, code.toUpperCase());
        if (ids.isEmpty()) throw ApiException.badRequest("Unknown location '" + code + "'.");
        return ids.get(0);
    }

    private String siteCode(UUID siteId) { return jdbc.queryForObject("SELECT code FROM org_node WHERE org_node_id = ?", String.class, siteId); }
}
