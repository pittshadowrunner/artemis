package com.artemis.wms.inbound;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.masterdata.ItemService;
import com.artemis.wms.masterdata.LocationService;
import com.artemis.wms.org.PolicyService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.artemis.wms.platform.Values.*;

/**
 * Receiving is a linkable document with a timeline (expected → arrived →
 * receiving → closed). Its output is pallets: one call per LPN, capture
 * enforced at the dock, minimum-shelf-life rejection sends product back on
 * the truck, and every pallet spawns its directed putaway task.
 */
@Service
public class ReceivingService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final ItemService items;
    private final LocationService locations;
    private final PolicyService policy;
    private final PutawayService putaway;

    public ReceivingService(JdbcTemplate jdbc, AccessService access, ItemService items, LocationService locations, PolicyService policy, PutawayService putaway) {
        this.jdbc = jdbc; this.access = access; this.items = items; this.locations = locations; this.policy = policy; this.putaway = putaway;
    }

    @Transactional
    public UUID createManifest(UUID siteId, Map<String, Object> m) {
        UUID corp = access.requireTenant();
        access.require(siteId, Capabilities.RECEIVING_EXECUTE);
        String number = upper(m.get("manifestNumber"));
        if (number == null) throw ApiException.badRequest("manifestNumber is required.");
        List<Map<String, Object>> lines = maps(m.get("lines"));
        if (lines.isEmpty()) throw ApiException.badRequest("A manifest needs at least one line.");
        List<UUID> existing = jdbc.queryForList("SELECT manifest_id FROM receiving_manifest WHERE site_id = ? AND manifest_number = ?", UUID.class, siteId, number);
        if (!existing.isEmpty()) return existing.get(0);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO receiving_manifest (manifest_id, corporation_id, site_id, manifest_number, carrier, trailer_number, expected_date) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, corp, siteId, number, str(m.get("carrier")), str(m.get("trailer")), date(m.get("expectedDate")));
        int n = 0;
        for (Map<String, Object> line : lines) {
            n++;
            BigDecimal qty = number(line.get("expectedQty"));
            if (qty == null || qty.signum() <= 0) throw ApiException.badRequest("Line " + n + ": expectedQty must be positive.");
            jdbc.update("INSERT INTO receiving_manifest_line (manifest_id, line_number, item_id, expected_qty) VALUES (?, ?, ?, ?)",
                id, n, items.idBySku(corp, str(line.get("sku"))), qty);
        }
        return id;
    }

    @Transactional
    public void arrive(UUID manifestId) {
        int n = jdbc.update("UPDATE receiving_manifest SET status = 'ARRIVED', arrived_at = now(), updated_at = now() WHERE manifest_id = ? AND status = 'EXPECTED'", manifestId);
        if (n == 0) throw ApiException.conflict("Manifest is not EXPECTED.");
    }

    /** One call per pallet. Returns the pallet and its directed putaway task. */
    @Transactional
    public Map<String, Object> receive(UUID manifestId, Map<String, Object> r) {
        Map<String, Object> m = jdbc.queryForMap("SELECT corporation_id, site_id, status::text AS status FROM receiving_manifest WHERE manifest_id = ?", manifestId);
        UUID siteId = (UUID) m.get("site_id");
        access.require(siteId, Capabilities.RECEIVING_EXECUTE);
        if (!List.of("ARRIVED", "RECEIVING").contains(m.get("status")))
            throw ApiException.conflict("Manifest must be ARRIVED before receiving (is " + m.get("status") + ").");

        Integer lineNo = integer(r.get("lineNumber"));
        if (lineNo == null) throw ApiException.badRequest("lineNumber is required.");
        Map<String, Object> line;
        try {
            line = jdbc.queryForMap("""
                SELECT ml.manifest_line_id, ml.item_id, ml.expected_qty, ml.received_qty, i.sku::text AS sku, i.lot_tracked, i.expiry_tracked,
                       i.serial_tracked, i.catch_weight, i.min_shelf_life_receipt_days AS item_min_receipt
                FROM receiving_manifest_line ml JOIN item i ON i.item_id = ml.item_id WHERE ml.manifest_id = ? AND ml.line_number = ?""", manifestId, lineNo);
        } catch (Exception e) { throw ApiException.notFound("Manifest line " + lineNo + " not found."); }

        BigDecimal qty = number(r.get("qty"));
        if (qty == null || qty.signum() <= 0) throw ApiException.badRequest("qty must be positive.");
        BigDecimal expected = (BigDecimal) line.get("expected_qty"), already = (BigDecimal) line.get("received_qty");
        if (already.add(qty).compareTo(expected.multiply(new BigDecimal("1.10"))) > 0)
            throw ApiException.conflict("Over-receipt beyond 10% of expected — correct the manifest line first.");

        String lot = str(r.get("lot"));
        if (is(line.get("lot_tracked")) && lot == null) throw ApiException.badRequest("Lot-tracked item requires a lot at the dock.");
        LocalDate exp = date(r.get("expirationDate"));
        if (is(line.get("expiry_tracked"))) {
            if (exp == null) throw ApiException.badRequest("Expiry-tracked item requires an expiration date at the dock.");
            Integer min = (Integer) line.get("item_min_receipt");
            if (min == null) min = policy.minReceiptDays(siteId);
            if (min != null) {
                long remaining = ChronoUnit.DAYS.between(LocalDate.now(), exp);
                if (remaining < min) throw ApiException.conflict("Rejected: " + remaining + " days remaining is under the " + min + "-day minimum receipt shelf life. Put it back on the truck.");
            }
        }
        List<String> serials = strings(r.get("serials"));
        if (is(line.get("serial_tracked")) && serials.size() != qty.intValue())
            throw ApiException.badRequest("Serial-tracked item needs exactly " + qty.intValue() + " serials.");
        BigDecimal weight = number(r.get("actualWeightKg"));
        if (is(line.get("catch_weight")) && weight == null) throw ApiException.badRequest("Catch-weight item requires the scale reading (actualWeightKg).");
        String lpn = upper(r.get("lpn"));
        if (lpn == null) throw ApiException.badRequest("A receipt is an LPN — supply one.");

        UUID dock = r.get("dock") != null ? locations.idByCode(siteId, str(r.get("dock"))) : defaultDock(siteId);
        UUID itemId = (UUID) line.get("item_id");
        UUID invId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO inventory (inventory_id, corporation_id, site_id, lpn, item_id, location_id, qty, original_qty, lot_number, expiration_date, actual_weight_kg, manifest_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", invId, m.get("corporation_id"), siteId, lpn, itemId, dock, qty, qty, lot, exp, weight, manifestId);
        for (String s : serials) jdbc.update("INSERT INTO inventory_serial (inventory_id, serial_number) VALUES (?, ?)", invId, s);
        jdbc.update("INSERT INTO inventory_movement (inventory_id, to_location, qty, movement_type, performed_by) VALUES (?, ?, ?, 'RECEIPT', ?)", invId, dock, qty, TenantContext.user());
        jdbc.update("UPDATE receiving_manifest_line SET received_qty = received_qty + ? WHERE manifest_line_id = ?", qty, line.get("manifest_line_id"));
        jdbc.update("UPDATE receiving_manifest SET status = 'RECEIVING', updated_at = now() WHERE manifest_id = ? AND status = 'ARRIVED'", manifestId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inventoryId", invId); out.put("lpn", lpn); out.put("sku", line.get("sku")); out.put("qty", qty);
        if (!is(r.get("skipPutaway"))) out.put("putaway", putaway.createTask(siteId, invId, itemId, lot, dock));
        return out;
    }

    private UUID defaultDock(UUID siteId) {
        List<UUID> docks = jdbc.queryForList("SELECT location_id FROM location WHERE site_id = ? AND loc_type = 'RECEIVING_DOCK' AND active ORDER BY code LIMIT 1", UUID.class, siteId);
        if (docks.isEmpty()) throw ApiException.badRequest("No RECEIVING_DOCK location at this site.");
        return docks.get(0);
    }

    @Transactional
    public void reject(UUID manifestId, int lineNumber, BigDecimal qty, String reason) {
        if (qty == null || qty.signum() <= 0) throw ApiException.badRequest("qty must be positive.");
        if (reason == null) throw ApiException.badRequest("A rejection needs a reason.");
        int n = jdbc.update("UPDATE receiving_manifest_line SET rejected_qty = rejected_qty + ?, rejection_reason = ? WHERE manifest_id = ? AND line_number = ?", qty, reason, manifestId, lineNumber);
        if (n == 0) throw ApiException.notFound("Manifest line not found.");
    }

    /** Short shipments close fine — deliberately. */
    @Transactional
    public void close(UUID manifestId) {
        int n = jdbc.update("UPDATE receiving_manifest SET status = 'CLOSED', closed_at = now(), updated_at = now() WHERE manifest_id = ? AND status IN ('ARRIVED','RECEIVING','RECEIVED')", manifestId);
        if (n == 0) throw ApiException.conflict("Manifest cannot be closed from its current status.");
    }

    public List<Map<String, Object>> list(UUID siteId, boolean openOnly) {
        return jdbc.queryForList("SELECT * FROM v_receiving_progress WHERE site_id = ? AND (NOT ? OR status NOT IN ('CLOSED','CANCELLED')) ORDER BY expected_date NULLS LAST, manifest_number", siteId, openOnly);
    }

    public Map<String, Object> get(UUID manifestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM v_receiving_progress WHERE manifest_id = ?", manifestId);
        if (rows.isEmpty()) throw ApiException.notFound("Manifest not found.");
        Map<String, Object> m = new LinkedHashMap<>(rows.get(0));
        m.put("lines", jdbc.queryForList("""
            SELECT ml.line_number, i.sku::text AS sku, i.description, ml.expected_qty, ml.received_qty, ml.rejected_qty, ml.rejection_reason
            FROM receiving_manifest_line ml JOIN item i ON i.item_id = ml.item_id WHERE ml.manifest_id = ? ORDER BY ml.line_number""", manifestId));
        m.put("pallets", jdbc.queryForList("""
            SELECT v.lpn, i.sku::text AS sku, v.lot_number, v.expiration_date, v.qty, v.actual_weight_kg, l.code AS location, v.status::text AS status, v.created_at
            FROM inventory v JOIN item i ON i.item_id = v.item_id LEFT JOIN location l ON l.location_id = v.location_id
            WHERE v.manifest_id = ? ORDER BY v.created_at""", manifestId));
        return m;
    }
}
