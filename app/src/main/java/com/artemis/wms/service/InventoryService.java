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
import java.time.LocalDate;
import java.util.*;

import static com.artemis.wms.service.LocationService.*;

/**
 * Opening balances — the one path where stock enters without going through
 * Receiving, so it validates harder than Receiving does.
 */
@Service
public class InventoryService {

    private final JdbcTemplate jdbc;

    public InventoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public BulkResult load(UUID siteId, boolean replaceExisting, List<Map<String, Object>> records) {
        if (replaceExisting) {
            jdbc.update("DELETE FROM inventory WHERE site_id = ? AND status = 'AVAILABLE'", siteId);
        }
        BulkResult result = new BulkResult();
        int row = 0;
        for (Map<String, Object> rec : records) {
            row++;
            String lpn = str(rec.get("lpn"));
            try {
                loadOne(siteId, rec, lpn);
                result.created();
            } catch (ApiException e) {
                result.error(row, lpn, e.getMessage());
            } catch (Exception e) {
                result.error(row, lpn, rootMessage(e));
            }
        }
        return result;
    }

    private void loadOne(UUID siteId, Map<String, Object> rec, String lpn) {
        String sku = str(rec.get("sku"));
        Map<String, Object> item;
        try {
            item = jdbc.queryForMap("""
                SELECT item_id, lot_tracked, expiry_tracked, serial_tracked, temp_zone::text AS temp_zone
                FROM item WHERE corporation_id = ? AND sku = ?::citext
                """, TenantContext.corp(), sku);
        } catch (Exception e) { throw ApiException.badRequest("Unknown SKU '" + sku + "'."); }

        String locCode = str(rec.get("location"));
        Map<String, Object> loc;
        try {
            loc = jdbc.queryForMap(
                "SELECT location_id, temp_zone::text AS temp_zone FROM location WHERE site_id = ? AND code = ?",
                siteId, locCode);
        } catch (Exception e) { throw ApiException.badRequest("Unknown location '" + locCode + "'."); }

        if (!Objects.equals(item.get("temp_zone"), loc.get("temp_zone")))
            throw ApiException.badRequest("Temperature mismatch: " + item.get("temp_zone")
                    + " item cannot be loaded into " + loc.get("temp_zone") + " location " + locCode + ".");

        String lot = str(rec.get("lotNumber"));
        if (Boolean.TRUE.equals(item.get("lot_tracked")) && lot == null)
            throw ApiException.badRequest("Lot-tracked item requires a lot number — recall traceability depends on it.");

        LocalDate exp = date(rec.get("expirationDate"));
        if (Boolean.TRUE.equals(item.get("expiry_tracked"))) {
            if (exp == null)
                throw ApiException.badRequest("Expiry-tracked item requires an expiration date — FEFO depends on it.");
            if (!exp.isAfter(LocalDate.now()))
                throw ApiException.badRequest("Expiration date " + exp + " is already past.");
        }

        BigDecimal qty = num(rec.get("qty"), "qty");
        if (qty == null || qty.signum() <= 0) throw ApiException.badRequest("Quantity must be positive.");

        List<String> serials = serials(rec.get("serials"));
        if (Boolean.TRUE.equals(item.get("serial_tracked"))
                && serials.size() != qty.intValue())
            throw ApiException.badRequest("Serial-tracked item needs exactly " + qty.intValue()
                    + " serials, got " + serials.size() + ".");

        UUID invId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO inventory (inventory_id, corporation_id, site_id, lpn, item_id, location_id,
                qty, lot_number, expiration_date, arrival_date, actual_weight_kg)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_DATE), ?)
            """, invId, TenantContext.corp(), siteId, lpn, item.get("item_id"), loc.get("location_id"),
            qty, lot, exp, date(rec.get("arrivalDate")), num(rec.get("actualWeightKg"), ""));
        for (String s : serials)
            jdbc.update("INSERT INTO inventory_serial (inventory_id, serial_number) VALUES (?, ?)", invId, s);
        jdbc.update("""
            INSERT INTO inventory_movement (inventory_id, to_location, qty, movement_type, performed_by)
            VALUES (?, ?, ?, 'OPENING_BALANCE', ?)
            """, invId, loc.get("location_id"), qty, TenantContext.user());
    }

    @Transactional
    public BulkResult uploadFile(UUID siteId, boolean replaceExisting, MultipartFile file) {
        List<Map<String, String>> rows = RowSource.read(file);
        List<Map<String, Object>> records = rows.stream().map(r -> {
            Map<String, Object> rec = new HashMap<>();
            rec.put("lpn", r.get("lpn")); rec.put("sku", r.get("sku"));
            rec.put("location", r.get("location")); rec.put("qty", r.get("qty"));
            rec.put("lotNumber", r.get("lotnumber")); rec.put("expirationDate", r.get("expirationdate"));
            rec.put("arrivalDate", r.get("arrivaldate")); rec.put("actualWeightKg", r.get("actualweightkg"));
            rec.put("serials", r.get("serials"));
            return rec;
        }).toList();
        return load(siteId, replaceExisting, records);
    }

    public static LocalDate date(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return LocalDate.parse(o.toString()); }
        catch (Exception e) { throw ApiException.badRequest("'" + o + "' isn't a date (use YYYY-MM-DD)."); }
    }

    @SuppressWarnings("unchecked")
    public static List<String> serials(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return l.stream().map(Object::toString).toList();
        String s = o.toString().trim();
        return s.isEmpty() ? List.of() : Arrays.asList(s.split("\\s*[,;|]\\s*"));
    }
}
