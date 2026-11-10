package com.artemis.wms.inventory;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.masterdata.ItemService;
import com.artemis.wms.masterdata.LocationService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.platform.BulkResult;
import com.artemis.wms.platform.RowSource;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.artemis.wms.platform.Values.*;

/**
 * Pallets. Opening balances are the one path where stock enters without going
 * through Receiving, so they validate harder than Receiving does. Each row
 * commits alone: a convention rejection fails that pallet, not the file.
 */
@Service
public class InventoryService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final ItemService items;
    private final LocationService locations;
    private final TransactionTemplate tx;
    private final com.artemis.wms.platform.BulkLoads loads;

    public InventoryService(JdbcTemplate jdbc, AccessService access, ItemService items, LocationService locations, TransactionTemplate tx, com.artemis.wms.platform.BulkLoads loads) {
        this.jdbc = jdbc; this.access = access; this.items = items; this.locations = locations; this.tx = tx; this.loads = loads;
    }

    public BulkResult openingBalances(UUID siteId, List<Map<String, Object>> records) {
        UUID corp = access.requireTenant();
        access.require(siteId, Capabilities.INVENTORY_ADJUST);
        return loads.during(corp, () -> load(corp, siteId, records));
    }

    private BulkResult load(UUID corp, UUID siteId, List<Map<String, Object>> records) {
        BulkResult r = new BulkResult();
        int row = 0;
        for (Map<String, Object> rec : records) {
            row++;
            String lpn = upper(rec.get("lpn"));
            try {
                if (lpn == null) throw ApiException.badRequest("lpn is required — a pallet is an LPN.");
                tx.executeWithoutResult(s -> loadOne(corp, siteId, lpn, rec));
                r.created();
            } catch (ApiException e) { r.error(row, lpn, e.getMessage()); }
              catch (Exception e)    { r.error(row, lpn, rootMessage(e)); }
        }
        return r;
    }

    private void loadOne(UUID corp, UUID siteId, String lpn, Map<String, Object> rec) {
        Integer dup = jdbc.queryForObject("SELECT count(*) FROM inventory WHERE site_id = ? AND lpn = ?", Integer.class, siteId, lpn);
        if (dup != null && dup > 0) throw ApiException.conflict("LPN already exists at this site.");
        UUID itemId = items.idBySku(corp, str(rec.get("sku")));
        Map<String, Object> item = jdbc.queryForMap("SELECT lot_tracked, expiry_tracked, serial_tracked, catch_weight FROM item WHERE item_id = ?", itemId);
        UUID locationId = locations.idByCode(siteId, str(rec.get("location")));
        BigDecimal qty = number(rec.get("qty"));
        if (qty == null || qty.signum() <= 0) throw ApiException.badRequest("qty must be positive.");
        String lot = str(rec.get("lot"));
        if (is(item.get("lot_tracked")) && lot == null) throw ApiException.badRequest("Lot-tracked item requires a lot — recall traceability depends on it.");
        LocalDate exp = date(rec.get("expirationDate"));
        if (is(item.get("expiry_tracked"))) {
            if (exp == null) throw ApiException.badRequest("Expiry-tracked item requires an expiration date — FEFO depends on it.");
            if (!exp.isAfter(LocalDate.now())) throw ApiException.badRequest("Expiration date " + exp + " is already past.");
        }
        List<String> serials = strings(rec.get("serials"));
        if (is(item.get("serial_tracked")) && serials.size() != qty.intValue())
            throw ApiException.badRequest("Serial-tracked item needs exactly " + qty.intValue() + " serials, got " + serials.size() + ".");
        BigDecimal weight = number(rec.get("actualWeightKg"));
        if (is(item.get("catch_weight")) && weight == null) throw ApiException.badRequest("Catch-weight item requires actualWeightKg.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO inventory (inventory_id, corporation_id, site_id, lpn, item_id, location_id, qty, original_qty, lot_number, expiration_date, arrival_date, actual_weight_kg)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_DATE), ?)""",
            id, corp, siteId, lpn, itemId, locationId, qty, qty, lot, exp, date(rec.get("arrivalDate")), weight);
        for (String s : serials) jdbc.update("INSERT INTO inventory_serial (inventory_id, serial_number) VALUES (?, ?)", id, s);
        jdbc.update("INSERT INTO inventory_movement (inventory_id, to_location, qty, movement_type, performed_by) VALUES (?, ?, ?, 'OPENING_BALANCE', ?)", id, locationId, qty, TenantContext.user());
    }

    public BulkResult upload(UUID siteId, MultipartFile file) {
        return openingBalances(siteId, RowSource.read(file).stream().map(InventoryService::fromRow).toList());
    }

    static Map<String, Object> fromRow(Map<String, Object> r) {
        Map<String, Object> rec = new HashMap<>();
        String[][] cols = {{"lpn","lpn"},{"sku","sku"},{"location","location"},{"qty","qty"},{"lot","lot"},{"lotnumber","lot"},
            {"expirationdate","expirationDate"},{"arrivaldate","arrivalDate"},{"actualweightkg","actualWeightKg"},{"serials","serials"}};
        for (String[] c : cols) if (r.get(c[0]) != null) rec.put(c[1], r.get(c[0]));
        return rec;
    }

    public List<Map<String, Object>> list(UUID siteId, String sku, String status) {
        return jdbc.queryForList("""
            SELECT v.inventory_id, v.lpn, i.sku::text AS sku, i.description, i.temp_zone::text AS temp_zone, l.code AS location, l.loc_type::text AS loc_type,
                   v.qty, v.original_qty, v.status::text AS status, v.lot_number, v.expiration_date, v.arrival_date, v.actual_weight_kg
            FROM inventory v JOIN item i ON i.item_id = v.item_id LEFT JOIN location l ON l.location_id = v.location_id
            WHERE v.site_id = ? AND (?::text IS NULL OR i.sku = ?::citext) AND (?::text IS NULL OR v.status::text = ?)
            ORDER BY i.sku, v.expiration_date NULLS LAST, v.arrival_date""", siteId, sku, sku, status, status);
    }

    /** LPN lookup: the pallet, its attributes, its placard and its full history with dwell. */
    public Map<String, Object> pallet(String lpn) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT v.inventory_id, v.lpn, v.site_id, s.code AS site, i.sku::text AS sku, i.description, i.temp_zone::text AS temp_zone, tag_of(i.temp_zone::text) AS zone_tag,
                   l.code AS location, l.loc_type::text AS loc_type, l.check_digits,
                   v.qty, v.original_qty, v.status::text AS status, v.lot_number, v.expiration_date, v.arrival_date, v.actual_weight_kg,
                   m.manifest_number, v.manifest_id, v.created_at
            FROM inventory v JOIN item i ON i.item_id = v.item_id JOIN org_node s ON s.org_node_id = v.site_id
            LEFT JOIN location l ON l.location_id = v.location_id LEFT JOIN receiving_manifest m ON m.manifest_id = v.manifest_id
            WHERE v.corporation_id = ? AND v.lpn = ?""", access.requireTenant(), lpn.toUpperCase());
        if (rows.isEmpty()) throw ApiException.notFound("LPN " + lpn + " not found.");
        Map<String, Object> p = new LinkedHashMap<>(rows.get(0));
        p.put("serials", jdbc.queryForList("SELECT serial_number FROM inventory_serial WHERE inventory_id = ? ORDER BY 1", String.class, p.get("inventory_id")));
        p.put("history", jdbc.queryForList("""
            SELECT mv.movement_type, lf.code AS from_location, lt.code AS to_location, mv.qty, u.display_name AS performed_by, a.assignment_number, mv.created_at,
                   lead(mv.created_at) OVER (ORDER BY mv.created_at) - mv.created_at AS dwell
            FROM inventory_movement mv LEFT JOIN location lf ON lf.location_id = mv.from_location LEFT JOIN location lt ON lt.location_id = mv.to_location
            LEFT JOIN app_user u ON u.user_id = mv.performed_by LEFT JOIN assignment a ON a.assignment_id = mv.assignment_id
            WHERE mv.inventory_id = ? ORDER BY mv.created_at""", p.get("inventory_id")));
        return p;
    }
}
