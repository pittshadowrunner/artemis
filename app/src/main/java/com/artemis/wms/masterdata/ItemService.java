package com.artemis.wms.masterdata;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.platform.BulkResult;
import com.artemis.wms.platform.RowSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

import static com.artemis.wms.platform.Values.*;

/**
 * Item master. Bulk loads are idempotent (upsert by SKU) and per-row atomic:
 * one bad row fails alone. Base-item links resolve in a second pass because
 * catalog exports routinely list a variant before its base.
 */
@Service
public class ItemService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final TransactionTemplate tx;
    public ItemService(JdbcTemplate jdbc, AccessService access, TransactionTemplate tx) { this.jdbc = jdbc; this.access = access; this.tx = tx; }

    public BulkResult bulk(List<Map<String, Object>> items) {
        UUID corp = access.requireTenant();
        access.require(null, Capabilities.ITEM_MANAGE);
        BulkResult result = new BulkResult();
        Map<String, String> baseLinks = new LinkedHashMap<>();
        int row = 0;
        for (Map<String, Object> it : items) {
            row++;
            String sku = str(it.get("sku"));
            try {
                if (sku == null) throw ApiException.badRequest("sku is required.");
                boolean created = tx.execute(s -> upsert(corp, it));
                if (created) result.created(); else result.updated();
                String base = str(it.get("baseItemSku"));
                if (base != null) baseLinks.put(sku, base);
            } catch (ApiException e) { result.error(row, sku, e.getMessage()); }
              catch (Exception e)    { result.error(row, sku, rootMessage(e)); }
        }
        for (var link : baseLinks.entrySet()) {
            int n = jdbc.update("UPDATE item SET base_item_id = (SELECT item_id FROM item WHERE corporation_id = ? AND sku = ?::citext) WHERE corporation_id = ? AND sku = ?::citext AND EXISTS (SELECT 1 FROM item WHERE corporation_id = ? AND sku = ?::citext)",
                corp, link.getValue(), corp, link.getKey(), corp, link.getValue());
            if (n == 0) result.error(0, link.getKey(), "Base item '" + link.getValue() + "' not found.");
        }
        return result;
    }

    /** @return true if created, false if updated */
    private boolean upsert(UUID corp, Map<String, Object> it) {
        Object[] args = {
            str(it.get("description")), str(it.get("uom")), number(it.get("weightKg")), number(it.get("lengthCm")), number(it.get("widthCm")), number(it.get("heightCm")),
            bool(it.get("serialTracked")), bool(it.get("lotTracked")), bool(it.get("expiryTracked")), integer(it.get("shelfLifeDays")),
            upper(it.get("tempZone")), number(it.get("minTempC")), number(it.get("maxTempC")),
            bool(it.get("catchWeight")), number(it.get("nominalWeightKg")), upper(it.get("dateLabel")),
            integer(it.get("minShelfLifeReceiptDays")), integer(it.get("minShelfLifeShipDays")),
            array(it.get("allergens")), array(it.get("certifications")), str(it.get("countryOfOrigin")),
            str(it.get("gtinEach")), str(it.get("gtinCase")), integer(it.get("innerPackQty")), integer(it.get("casePackQty")), integer(it.get("palletTi")), integer(it.get("palletHi")),
            str(it.get("hazmatClass")), str(it.get("unNumber")), bool(it.get("fragile")), bool(it.get("thisSideUp")), integer(it.get("stackLimit")), bool(it.get("conveyable")), bool(it.get("crushable")),
            upper(it.get("serialCaptureAt")), number(it.get("retailPrice")), number(it.get("unitCost")),
            str(it.get("styleCode")), str(it.get("colorCode")), str(it.get("sizeCode")), str(it.get("seasonCode")), upper(it.get("velocityClass")) };
        if (args[0] == null) throw ApiException.badRequest("description is required.");
        String sku = str(it.get("sku"));
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM item WHERE corporation_id = ? AND sku = ?::citext", Integer.class, corp, sku);
        if (exists != null && exists > 0) {
            List<Object> upd = new ArrayList<>(Arrays.asList(args)); upd.add(corp); upd.add(sku);
            jdbc.update("""
                UPDATE item SET description = ?, uom = COALESCE(?, uom), weight_kg = COALESCE(?, weight_kg), length_cm = COALESCE(?, length_cm), width_cm = COALESCE(?, width_cm), height_cm = COALESCE(?, height_cm),
                    serial_tracked = COALESCE(?, serial_tracked), lot_tracked = COALESCE(?, lot_tracked), expiry_tracked = COALESCE(?, expiry_tracked), shelf_life_days = COALESCE(?, shelf_life_days),
                    temp_zone = COALESCE(?::temp_zone, temp_zone), min_temp_c = COALESCE(?, min_temp_c), max_temp_c = COALESCE(?, max_temp_c),
                    catch_weight = COALESCE(?, catch_weight), nominal_weight_kg = COALESCE(?, nominal_weight_kg), date_label = COALESCE(?::date_label_type, date_label),
                    min_shelf_life_receipt_days = COALESCE(?, min_shelf_life_receipt_days), min_shelf_life_ship_days = COALESCE(?, min_shelf_life_ship_days),
                    allergens = COALESCE(?, allergens), certifications = COALESCE(?, certifications), country_of_origin = COALESCE(?, country_of_origin),
                    gtin_each = COALESCE(?, gtin_each), gtin_case = COALESCE(?, gtin_case), inner_pack_qty = COALESCE(?, inner_pack_qty), case_pack_qty = COALESCE(?, case_pack_qty), pallet_ti = COALESCE(?, pallet_ti), pallet_hi = COALESCE(?, pallet_hi),
                    hazmat_class = COALESCE(?, hazmat_class), un_number = COALESCE(?, un_number), fragile = COALESCE(?, fragile), this_side_up = COALESCE(?, this_side_up), stack_limit = COALESCE(?, stack_limit), conveyable = COALESCE(?, conveyable), crushable = COALESCE(?, crushable),
                    serial_capture_at = COALESCE(?::serial_capture_point, serial_capture_at), retail_price = COALESCE(?, retail_price), unit_cost = COALESCE(?, unit_cost),
                    style_code = COALESCE(?, style_code), color_code = COALESCE(?, color_code), size_code = COALESCE(?, size_code), season_code = COALESCE(?, season_code), velocity_class = COALESCE(?, velocity_class),
                    active = true, updated_at = now()
                WHERE corporation_id = ? AND sku = ?::citext""", upd.toArray());
            return false;
        }
        List<Object> ins = new ArrayList<>(); ins.add(corp); ins.add(sku); ins.addAll(Arrays.asList(args));
        jdbc.update("""
            INSERT INTO item (corporation_id, sku, description, uom, weight_kg, length_cm, width_cm, height_cm,
                serial_tracked, lot_tracked, expiry_tracked, shelf_life_days, temp_zone, min_temp_c, max_temp_c,
                catch_weight, nominal_weight_kg, date_label, min_shelf_life_receipt_days, min_shelf_life_ship_days,
                allergens, certifications, country_of_origin, gtin_each, gtin_case, inner_pack_qty, case_pack_qty, pallet_ti, pallet_hi,
                hazmat_class, un_number, fragile, this_side_up, stack_limit, conveyable, crushable,
                serial_capture_at, retail_price, unit_cost, style_code, color_code, size_code, season_code, velocity_class)
            VALUES (?, ?::citext, ?, COALESCE(?, 'EA'), ?, ?, ?, ?,
                COALESCE(?, false), COALESCE(?, false), COALESCE(?, false), ?, COALESCE(?::temp_zone, 'AMBIENT'), ?, ?,
                COALESCE(?, false), ?, COALESCE(?::date_label_type, 'NONE'), ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, COALESCE(?, false), COALESCE(?, false), ?, COALESCE(?, true), COALESCE(?, false),
                COALESCE(?::serial_capture_point, 'NONE'), ?, ?, ?, ?, ?, ?, ?)""", ins.toArray());
        return true;
    }

    public BulkResult upload(MultipartFile file) {
        return bulk(RowSource.read(file).stream().map(ItemService::fromRow).toList());
    }

    /** CSV/XLSX headers → API field names. */
    static Map<String, Object> fromRow(Map<String, Object> r) {
        Map<String, Object> it = new HashMap<>();
        String[][] cols = {{"sku","sku"},{"description","description"},{"uom","uom"},{"baseitemsku","baseItemSku"},{"weightkg","weightKg"},
            {"serialtracked","serialTracked"},{"lottracked","lotTracked"},{"expirytracked","expiryTracked"},{"shelflifedays","shelfLifeDays"},
            {"tempzone","tempZone"},{"catchweight","catchWeight"},{"nominalweightkg","nominalWeightKg"},{"datelabel","dateLabel"},
            {"velocityclass","velocityClass"},{"casepackqty","casePackQty"},{"palletti","palletTi"},{"pallethi","palletHi"},
            {"allergens","allergens"},{"certifications","certifications"},{"hazmatclass","hazmatClass"},{"countryoforigin","countryOfOrigin"},
            {"gtineach","gtinEach"},{"gtincase","gtinCase"},{"minshelflifereceiptdays","minShelfLifeReceiptDays"},{"minshelflifeshipdays","minShelfLifeShipDays"}};
        for (String[] c : cols) if (r.get(c[0]) != null) it.put(c[1], r.get(c[0]));
        return it;
    }

    public List<Map<String, Object>> list(String q, String tempZone) {
        return jdbc.queryForList("""
            SELECT i.item_id, i.sku::text AS sku, i.description, i.temp_zone::text AS temp_zone, tag_of(i.temp_zone::text) AS zone_tag,
                   i.uom, i.lot_tracked, i.expiry_tracked, i.serial_tracked, i.catch_weight, i.shelf_life_days, i.velocity_class,
                   i.case_pack_qty, i.pallet_ti, i.pallet_hi, b.sku::text AS base_item_sku, i.active
            FROM item i LEFT JOIN item b ON b.item_id = i.base_item_id
            WHERE i.corporation_id = ? AND (?::text IS NULL OR i.sku::text ILIKE '%' || ? || '%' OR i.description ILIKE '%' || ? || '%')
              AND (?::text IS NULL OR i.temp_zone::text = ?)
            ORDER BY i.sku""", access.requireTenant(), q, q, q, tempZone, tempZone);
    }

    public Map<String, Object> get(String sku) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT i.*, i.sku::text AS sku, b.sku::text AS base_item_sku FROM item i LEFT JOIN item b ON b.item_id = i.base_item_id WHERE i.corporation_id = ? AND i.sku = ?::citext", access.requireTenant(), sku);
        if (rows.isEmpty()) throw ApiException.notFound("Item " + sku + " not found.");
        Map<String, Object> it = deArray(new LinkedHashMap<>(rows.get(0)));
        it.put("uoms", jdbc.queryForList("SELECT code, qty, of_code FROM item_uom WHERE item_id = ? ORDER BY qty", it.get("item_id")));
        return it;
    }

    public UUID idBySku(UUID corp, String sku) {
        List<UUID> ids = jdbc.queryForList("SELECT item_id FROM item WHERE corporation_id = ? AND sku = ?::citext", UUID.class, corp, sku);
        if (ids.isEmpty()) throw ApiException.badRequest("Unknown SKU '" + sku + "'.");
        return ids.get(0);
    }
}
