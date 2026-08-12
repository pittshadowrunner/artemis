package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.common.BulkResult;
import com.artemis.wms.files.RowSource;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

import static com.artemis.wms.service.LocationService.*;

@Service
public class ItemService {

    private final JdbcTemplate jdbc;

    public ItemService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Two passes: insert every row first, then resolve base_item_sku links —
     * catalog exports routinely list a variant before its base item.
     */
    @Transactional
    public BulkResult bulk(List<Map<String, Object>> items) {
        BulkResult result = new BulkResult();
        Map<String, String> baseLinks = new LinkedHashMap<>();
        int row = 0;
        for (Map<String, Object> it : items) {
            row++;
            String sku = str(it.get("sku"));
            try {
                validate(it);
                insertOne(it);
                String baseSku = str(it.get("baseItemSku"));
                if (baseSku != null) baseLinks.put(sku, baseSku);
                result.created();
            } catch (ApiException e) {
                result.error(row, sku, e.getMessage());
            } catch (Exception e) {
                result.error(row, sku, rootMessage(e));
            }
        }
        for (var link : baseLinks.entrySet()) {
            int n = jdbc.update("""
                UPDATE item SET base_item_id =
                    (SELECT item_id FROM item WHERE corporation_id = ? AND sku = ?::citext)
                WHERE corporation_id = ? AND sku = ?::citext
                """, TenantContext.corp(), link.getValue(), TenantContext.corp(), link.getKey());
            if (n == 0) result.error(0, link.getKey(), "Base item '" + link.getValue() + "' not found.");
        }
        return result;
    }

    private void validate(Map<String, Object> it) {
        boolean expiry = Boolean.TRUE.equals(boolVal(it.get("expiryTracked")));
        if (expiry && intVal(it.get("shelfLifeDays"), "shelfLifeDays") == null
                && str(it.get("dateLabel")) == null)
            throw ApiException.badRequest(
                "Expiry-tracked item needs shelf_life_days or a date_label — FEFO would have nothing to sort on.");
        boolean cw = Boolean.TRUE.equals(boolVal(it.get("catchWeight")));
        if (cw && num(it.get("nominalWeightKg"), "nominalWeightKg") == null)
            throw ApiException.badRequest(
                "Catch-weight item needs nominal_weight_kg — no baseline to compare captured weights against.");
    }

    private void insertOne(Map<String, Object> it) {
        jdbc.update("""
            INSERT INTO item (corporation_id, sku, description, uom, weight_kg,
                serial_tracked, lot_tracked, expiry_tracked, shelf_life_days,
                temp_zone, catch_weight, nominal_weight_kg, date_label,
                min_shelf_life_receipt_days, min_shelf_life_ship_days,
                allergens, certifications, country_of_origin,
                gtin_each, gtin_case, inner_pack_qty, case_pack_qty, pallet_ti, pallet_hi,
                hazmat_class, un_number, velocity_class)
            VALUES (?, ?::citext, ?, COALESCE(?, 'EA'), ?,
                COALESCE(?, false), COALESCE(?, false), COALESCE(?, false), ?,
                COALESCE(?::temp_zone, 'AMBIENT'), COALESCE(?, false), ?, COALESCE(?::date_label_type, 'NONE'),
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, TenantContext.corp(), str(it.get("sku")), str(it.get("description")),
            str(it.get("uom")), num(it.get("weightKg"), "weightKg"),
            boolVal(it.get("serialTracked")), boolVal(it.get("lotTracked")), boolVal(it.get("expiryTracked")),
            intVal(it.get("shelfLifeDays"), "shelfLifeDays"),
            str(it.get("tempZone")), boolVal(it.get("catchWeight")),
            num(it.get("nominalWeightKg"), "nominalWeightKg"), str(it.get("dateLabel")),
            intVal(it.get("minShelfLifeReceiptDays"), ""), intVal(it.get("minShelfLifeShipDays"), ""),
            arr(it.get("allergens")), arr(it.get("certifications")), str(it.get("countryOfOrigin")),
            str(it.get("gtinEach")), str(it.get("gtinCase")),
            intVal(it.get("innerPackQty"), ""), intVal(it.get("casePackQty"), ""),
            intVal(it.get("palletTi"), ""), intVal(it.get("palletHi"), ""),
            str(it.get("hazmatClass")), str(it.get("unNumber")), str(it.get("velocityClass")));

        // Tiered UOMs derive automatically from the flat pack data:
        // 1 CS = casePack EA; 1 PL = ti*hi CS. (V12 backfilled legacy rows.)
        UUID newId = jdbc.queryForObject(
            "SELECT item_id FROM item WHERE corporation_id = ? AND sku = ?::citext",
            UUID.class, TenantContext.corp(), str(it.get("sku")));
        jdbc.update("""
            INSERT INTO item_uom (item_id, code, qty, of_code)
            SELECT item_id, 'CS', case_pack_qty, 'EA' FROM item
            WHERE item_id = ? AND case_pack_qty IS NOT NULL AND case_pack_qty > 0
            ON CONFLICT (item_id, code) DO NOTHING
            """, newId);
        jdbc.update("""
            INSERT INTO item_uom (item_id, code, qty, of_code)
            SELECT item_id, 'PL', pallet_ti * pallet_hi, 'CS' FROM item
            WHERE item_id = ? AND pallet_ti IS NOT NULL AND pallet_hi IS NOT NULL
              AND EXISTS (SELECT 1 FROM item_uom u WHERE u.item_id = item.item_id AND u.code = 'CS')
            ON CONFLICT (item_id, code) DO NOTHING
            """, newId);
    }

    @Transactional
    public BulkResult upload(MultipartFile file) {
        List<Map<String, String>> rows = RowSource.read(file);
        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> it = new HashMap<>();
            it.put("sku", r.get("sku")); it.put("description", r.get("description"));
            it.put("uom", r.get("uom")); it.put("baseItemSku", r.get("baseitemsku"));
            it.put("weightKg", r.get("weightkg"));
            it.put("serialTracked", r.get("serialtracked")); it.put("lotTracked", r.get("lottracked"));
            it.put("expiryTracked", r.get("expirytracked")); it.put("shelfLifeDays", r.get("shelflifedays"));
            it.put("tempZone", r.get("tempzone")); it.put("catchWeight", r.get("catchweight"));
            it.put("nominalWeightKg", r.get("nominalweightkg")); it.put("dateLabel", r.get("datelabel"));
            it.put("velocityClass", r.get("velocityclass"));
            it.put("casePackQty", r.get("casepackqty"));
            it.put("palletTi", r.get("palletti")); it.put("palletHi", r.get("pallethi"));
            return it;
        }).toList();
        return bulk(items);
    }

    @SuppressWarnings("unchecked")
    private String[] arr(Object o) {
        if (o == null) return null;
        if (o instanceof List<?> l) return l.stream().map(Object::toString).toArray(String[]::new);
        String s = o.toString().trim();
        return s.isEmpty() ? null : s.split("\\s*[,;]\\s*");
    }
}
