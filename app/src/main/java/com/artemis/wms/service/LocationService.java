package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.common.BulkResult;
import com.artemis.wms.files.RowSource;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LocationService {

    private final JdbcTemplate jdbc;

    public LocationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public BulkResult bulk(UUID siteId, boolean skipExisting, List<Map<String, Object>> locations) {
        BulkResult result = new BulkResult();
        int row = 0;
        for (Map<String, Object> loc : locations) {
            row++;
            String code = str(loc.get("code"));
            try {
                insertOne(siteId, skipExisting, loc, result, row, code);
            } catch (ApiException e) {
                result.error(row, code, e.getMessage());
            } catch (Exception e) {
                result.error(row, code, rootMessage(e));
            }
        }
        return result;
    }

    private void insertOne(UUID siteId, boolean skipExisting, Map<String, Object> loc,
                           BulkResult result, int row, String code) {
        Integer exists = jdbc.queryForObject(
            "SELECT count(*) FROM location WHERE site_id = ? AND code = ?", Integer.class, siteId, code);
        if (exists != null && exists > 0) {
            if (skipExisting) { result.skippedRow(); return; }
            throw ApiException.conflict("Location already exists at this site.");
        }
        String tempZone = str(loc.get("tempZone"));
        if (tempZone != null && !validEnum("temp_zone", tempZone))
            throw ApiException.badRequest("Unknown temperature zone '" + tempZone + "'.");
        String replenSku = str(loc.get("replenSku"));
        UUID replenItem = null;
        if (replenSku != null) {
            List<UUID> items = jdbc.queryForList(
                "SELECT item_id FROM item WHERE corporation_id = ? AND sku = ?::citext",
                UUID.class, TenantContext.corp(), replenSku);
            if (items.isEmpty()) throw ApiException.badRequest("Unknown replen SKU '" + replenSku + "'.");
            replenItem = items.get(0);
        }
        jdbc.update("""
            INSERT INTO location (corporation_id, site_id, area_id, code, loc_type, aisle, bay, tier, slot,
                pick_sequence, temp_zone, rack_type, velocity_zone, golden_zone, equipment_class,
                hazmat_approved, single_item, replen_item_id, replen_min_qty, replen_max_qty, replen_trigger_qty)
            VALUES (?, ?, ?, ?, ?::location_type, ?, ?, ?, ?, ?, COALESCE(?::temp_zone,'AMBIENT'), ?::rack_type,
                    ?, COALESCE(?, false), ?, COALESCE(?, false), COALESCE(?, false), ?, ?, ?, ?)
            """, TenantContext.corp(), siteId, uuid(loc.get("areaId")), code, str(loc.get("locType")),
            str(loc.get("aisle")), str(loc.get("bay")), str(loc.get("tier")), str(loc.get("slot")),
            intVal(loc.get("pickSequence"), "pickSequence"), tempZone, str(loc.get("rackType")),
            str(loc.get("velocityZone")), boolVal(loc.get("goldenZone")), str(loc.get("equipmentClass")),
            boolVal(loc.get("hazmatApproved")), boolVal(loc.get("singleItem")),
            replenItem, num(loc.get("replenMinQty"), "replenMinQty"),
            num(loc.get("replenMaxQty"), "replenMaxQty"), num(loc.get("replenTriggerQty"), "replenTriggerQty"));
        // V5 backfill trigger only runs at migration time; assign digits for new rows
        jdbc.update("""
            UPDATE location SET check_digits = lpad((abs(hashtext(code)) % 100)::text, 2, '0')
            WHERE site_id = ? AND code = ? AND check_digits IS NULL
            """, siteId, code);
        result.created();
    }

    /**
     * Range generation: describe the rack, we enumerate it. Pick sequence
     * is assigned serpentine — up one aisle, down the next — because that's
     * how a picker actually walks.
     */
    @Transactional
    public BulkResult generate(Map<String, Object> spec) {
        UUID siteId = uuid(spec.get("siteId"));
        List<String> aisles = strList(spec.get("aisles"));
        List<String> bays = strList(spec.get("bays"));
        List<String> tiers = strList(spec.get("tiers"));
        List<String> slots = strList(spec.get("slots"));
        String pattern = spec.getOrDefault("pattern", "{aisle}-{bay}-{tier}-{slot}").toString();
        int seq = spec.get("pickSequenceStart") == null ? 1000 : Integer.parseInt(spec.get("pickSequenceStart").toString());
        int step = spec.get("pickSequenceStep") == null ? 10 : Integer.parseInt(spec.get("pickSequenceStep").toString());

        BulkResult result = new BulkResult();
        int row = 0;
        for (int a = 0; a < aisles.size(); a++) {
            List<String> walkBays = new java.util.ArrayList<>(bays);
            if (a % 2 == 1) java.util.Collections.reverse(walkBays);   // serpentine
            for (String bay : walkBays) {
                for (String tier : tiers) {
                    for (String slot : slots) {
                        row++;
                        String code = pattern.replace("{aisle}", aisles.get(a)).replace("{bay}", bay)
                                .replace("{tier}", tier).replace("{slot}", slot);
                        Map<String, Object> loc = new java.util.HashMap<>();
                        loc.put("code", code);
                        loc.put("locType", spec.getOrDefault("locType", "STORAGE"));
                        loc.put("aisle", aisles.get(a)); loc.put("bay", bay);
                        loc.put("tier", tier); loc.put("slot", slot);
                        loc.put("pickSequence", seq);
                        loc.put("tempZone", spec.get("tempZone"));
                        loc.put("rackType", spec.get("rackType"));
                        loc.put("areaId", spec.get("areaId"));
                        try {
                            insertOne(siteId, true, loc, result, row, code);
                        } catch (Exception e) {
                            result.error(row, code, rootMessage(e));
                        }
                        seq += step;
                    }
                }
            }
        }
        return result;
    }

    @Transactional
    public BulkResult upload(UUID siteId, MultipartFile file) {
        List<Map<String, String>> rows = RowSource.read(file);
        BulkResult result = new BulkResult();
        int rowNo = 0;
        for (Map<String, String> r : rows) {
            rowNo++;
            String code = r.get("code");
            try {
                Map<String, Object> loc = new java.util.HashMap<>();
                loc.put("code", code); loc.put("locType", blank(r.get("loctype")) ? "STORAGE" : r.get("loctype"));
                loc.put("aisle", r.get("aisle")); loc.put("bay", r.get("bay"));
                loc.put("tier", r.get("tier")); loc.put("slot", r.get("slot"));
                loc.put("pickSequence", r.get("picksequence"));
                loc.put("tempZone", r.get("tempzone")); loc.put("rackType", r.get("racktype"));
                loc.put("velocityZone", r.get("velocityzone")); loc.put("goldenZone", r.get("goldenzone"));
                loc.put("replenSku", r.get("replensku"));
                loc.put("replenMinQty", r.get("replenminqty")); loc.put("replenMaxQty", r.get("replenmaxqty"));
                loc.put("replenTriggerQty", r.get("replentriggerqty"));
                insertOne(siteId, false, loc, result, rowNo, code);
            } catch (ApiException e) {
                result.error(rowNo, code, e.getMessage());
            } catch (Exception e) {
                result.error(rowNo, code, rootMessage(e));
            }
        }
        return result;
    }

    private boolean validEnum(String type, String value) {
        Integer n = jdbc.queryForObject("""
            SELECT count(*) FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid
            WHERE t.typname = ? AND e.enumlabel = ?
            """, Integer.class, type, value);
        return n != null && n > 0;
    }

    @SuppressWarnings("unchecked")
    static List<String> strList(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return l.stream().map(Object::toString).toList();
        return java.util.Arrays.asList(o.toString().split("\\s*,\\s*"));
    }

    public static boolean blank(String s) { return s == null || s.isBlank(); }
    public static String str(Object o) { return o == null || o.toString().isBlank() ? null : o.toString(); }
    public static UUID uuid(Object o) { return o == null || o.toString().isBlank() ? null : UUID.fromString(o.toString()); }
    public static Boolean boolVal(Object o) { return o == null || o.toString().isBlank() ? null : Boolean.valueOf(o.toString()); }

    public static Integer intVal(Object o, String field) {
        if (o == null || o.toString().isBlank()) return null;
        try { return new BigDecimal(o.toString()).intValueExact(); }
        catch (Exception e) { throw ApiException.badRequest("'" + o + "' isn't a whole number."); }
    }

    public static BigDecimal num(Object o, String field) {
        if (o == null || o.toString().isBlank()) return null;
        try { return new BigDecimal(o.toString()); }
        catch (Exception e) { throw ApiException.badRequest("'" + o + "' isn't a number."); }
    }

    public static String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }
}
