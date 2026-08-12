package com.artemis.wms.service;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.artemis.wms.service.LocationService.*;
import static com.artemis.wms.service.InventoryService.date;
import static com.artemis.wms.service.InventoryService.serials;

@Service
public class ReceivingService {

    private final JdbcTemplate jdbc;
    private final PutawayService putaway;
    private final PolicyService policy;

    public ReceivingService(JdbcTemplate jdbc, PutawayService putaway, PolicyService policy) {
        this.jdbc = jdbc; this.putaway = putaway; this.policy = policy;
    }

    @Transactional
    public UUID createManifest(UUID siteId, String manifestNumber, String carrier, String trailer,
                               LocalDate expectedDate, List<Map<String, Object>> lines) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO receiving_manifest (manifest_id, corporation_id, site_id, manifest_number,
                carrier, trailer_number, expected_date)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, TenantContext.corp(), siteId, manifestNumber, carrier, trailer, expectedDate);
        int lineNo = 0;
        for (Map<String, Object> line : lines) {
            lineNo++;
            String sku = str(line.get("sku"));
            UUID itemId = itemBySku(sku);
            jdbc.update("""
                INSERT INTO receiving_manifest_line (manifest_id, line_number, item_id, expected_qty)
                VALUES (?, ?, ?, ?)
                """, id, lineNo, itemId, num(line.get("expectedQty"), "expectedQty"));
        }
        return id;
    }

    @Transactional
    public void arrive(UUID manifestId) {
        int n = jdbc.update(
            "UPDATE receiving_manifest SET status = 'ARRIVED', arrived_at = now() WHERE manifest_id = ? AND status = 'EXPECTED'",
            manifestId);
        if (n == 0) throw ApiException.conflict("Manifest is not in EXPECTED status.");
    }

    /**
     * One call per LPN. Capture enforced at the dock: lot-tracked needs a lot,
     * expiry-tracked needs a date, serial-tracked needs exactly qty serials,
     * catch-weight needs the scale reading. Minimum-shelf-life-at-receipt
     * rejects product that should go back on the truck.
     */
    @Transactional
    public Map<String, Object> receive(UUID manifestId, Map<String, Object> receipt) {
        Map<String, Object> manifest = jdbc.queryForMap(
            "SELECT site_id, status::text AS status FROM receiving_manifest WHERE manifest_id = ?", manifestId);
        String status = (String) manifest.get("status");
        if (!List.of("ARRIVED", "RECEIVING").contains(status))
            throw ApiException.conflict("Manifest must be ARRIVED before receiving (is " + status + ").");
        UUID siteId = (UUID) manifest.get("site_id");

        int lineNumber = Integer.parseInt(receipt.get("lineNumber").toString());
        Map<String, Object> line = jdbc.queryForMap("""
            SELECT ml.manifest_line_id, ml.item_id, ml.expected_qty, ml.received_qty,
                   i.sku, i.lot_tracked, i.expiry_tracked, i.serial_tracked, i.catch_weight,
                   i.nominal_weight_kg, i.min_shelf_life_receipt_days AS item_min_receipt,
                   i.shelf_life_days
            FROM receiving_manifest_line ml JOIN item i ON i.item_id = ml.item_id
            WHERE ml.manifest_id = ? AND ml.line_number = ?
            """, manifestId, lineNumber);

        BigDecimal qty = num(receipt.get("qty"), "qty");
        if (qty == null || qty.signum() <= 0) throw ApiException.badRequest("Quantity must be positive.");

        BigDecimal expected = (BigDecimal) line.get("expected_qty");
        BigDecimal already = (BigDecimal) line.get("received_qty");
        if (already.add(qty).compareTo(expected.multiply(new BigDecimal("1.10"))) > 0)
            throw ApiException.conflict("Over-receipt beyond 10% of expected — correct the manifest line first.");

        String lot = str(receipt.get("lot"));
        if (Boolean.TRUE.equals(line.get("lot_tracked")) && lot == null)
            throw ApiException.badRequest("Lot-tracked item requires a lot number at the dock.");
        LocalDate exp = date(receipt.get("expirationDate"));
        if (Boolean.TRUE.equals(line.get("expiry_tracked"))) {
            if (exp == null) throw ApiException.badRequest("Expiry-tracked item requires an expiration date at the dock.");
            Integer minReceipt = (Integer) line.get("item_min_receipt");
            if (minReceipt == null) minReceipt = policy.effectiveMinReceiptDays(siteId);
            if (minReceipt != null) {
                long remaining = LocalDate.now().until(exp).getDays()
                        + LocalDate.now().until(exp).toTotalMonths() * 30; // approx guard
                long daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), exp);
                if (daysRemaining < minReceipt)
                    throw ApiException.conflict("Rejected: " + daysRemaining + " days remaining is under the "
                            + minReceipt + "-day minimum receipt shelf life. Put it back on the truck.");
            }
        }
        List<String> serials = serials(receipt.get("serials"));
        if (Boolean.TRUE.equals(line.get("serial_tracked")) && serials.size() != qty.intValue())
            throw ApiException.badRequest("Serial-tracked item needs exactly " + qty.intValue() + " serials.");
        BigDecimal actualWeight = num(receipt.get("actualWeightKg"), "");
        if (Boolean.TRUE.equals(line.get("catch_weight")) && actualWeight == null)
            throw ApiException.badRequest("Catch-weight item requires the scale reading.");

        String lpn = str(receipt.get("lpn"));
        if (lpn == null) throw ApiException.badRequest("A receipt is an LPN — supply one.");
        UUID dockId = uuid(receipt.get("dockLocationId"));
        if (dockId == null) {
            List<UUID> docks = jdbc.queryForList("""
                SELECT location_id FROM location
                WHERE site_id = ? AND loc_type = 'RECEIVING_DOCK' AND active LIMIT 1
                """, UUID.class, siteId);
            if (docks.isEmpty()) throw ApiException.badRequest("No RECEIVING_DOCK location at this site.");
            dockId = docks.get(0);
        }

        UUID invId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO inventory (inventory_id, corporation_id, site_id, lpn, item_id, location_id,
                qty, original_qty, lot_number, expiration_date, arrival_date, actual_weight_kg, received_from_manifest)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_DATE, ?, ?)
            """, invId, TenantContext.corp(), siteId, lpn, line.get("item_id"), dockId,
            qty, qty, lot, exp, actualWeight, manifestId);
        for (String s : serials)
            jdbc.update("INSERT INTO inventory_serial (inventory_id, serial_number) VALUES (?, ?)", invId, s);
        jdbc.update("""
            INSERT INTO inventory_movement (inventory_id, to_location, qty, movement_type, performed_by)
            VALUES (?, ?, ?, 'RECEIPT', ?)
            """, invId, dockId, qty, TenantContext.user());
        jdbc.update("UPDATE receiving_manifest_line SET received_qty = received_qty + ? WHERE manifest_line_id = ?",
            qty, line.get("manifest_line_id"));
        jdbc.update("UPDATE receiving_manifest SET status = 'RECEIVING' WHERE manifest_id = ? AND status = 'ARRIVED'",
            manifestId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("inventoryId", invId);
        response.put("lpn", lpn);
        if (!Boolean.TRUE.equals(receipt.get("skipPutawayTask"))) {
            response.put("putawayTask", putaway.createTask(siteId, invId, (UUID) line.get("item_id"), lot, dockId));
        }
        return response;
    }

    @Transactional
    public void reject(UUID manifestId, int lineNumber, BigDecimal qty, String reason) {
        int n = jdbc.update("""
            UPDATE receiving_manifest_line SET rejected_qty = rejected_qty + ?, rejection_reason = ?
            WHERE manifest_id = ? AND line_number = ?
            """, qty, reason, manifestId, lineNumber);
        if (n == 0) throw ApiException.notFound("Manifest line not found.");
    }

    /** Short shipments allowed, deliberately. */
    @Transactional
    public void close(UUID manifestId) {
        int n = jdbc.update("""
            UPDATE receiving_manifest SET status = 'CLOSED', closed_at = now()
            WHERE manifest_id = ? AND status IN ('ARRIVED','RECEIVING','RECEIVED')
            """, manifestId);
        if (n == 0) throw ApiException.conflict("Manifest cannot be closed from its current status.");
    }

    public Map<String, Object> get(UUID manifestId) {
        Map<String, Object> m = jdbc.queryForMap("""
            SELECT manifest_id, manifest_number, carrier, trailer_number, expected_date, status::text AS status
            FROM receiving_manifest WHERE manifest_id = ?
            """, manifestId);
        m.put("lines", jdbc.queryForList("""
            SELECT ml.line_number, i.sku::text AS sku, i.description, ml.expected_qty, ml.received_qty, ml.rejected_qty,
                   ml.rejection_reason
            FROM receiving_manifest_line ml JOIN item i ON i.item_id = ml.item_id
            WHERE ml.manifest_id = ? ORDER BY ml.line_number
            """, manifestId));
        return m;
    }

    private UUID itemBySku(String sku) {
        List<UUID> ids = jdbc.queryForList(
            "SELECT item_id FROM item WHERE corporation_id = ? AND sku = ?::citext",
            UUID.class, TenantContext.corp(), sku);
        if (ids.isEmpty()) throw ApiException.badRequest("Unknown SKU '" + sku + "'.");
        return ids.get(0);
    }
}
