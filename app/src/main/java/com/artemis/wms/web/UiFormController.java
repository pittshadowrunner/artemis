package com.artemis.wms.web;

import com.artemis.wms.common.ApiException;
import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.service.LocationService.*;

/**
 * Form handlers behind the asset screens. Same capability gates as the REST
 * API — the UI is just another client, so a form post is authorized exactly
 * like the equivalent API call. Every handler redirects back with a flash
 * message; errors land in the same banner instead of a whitelabel page.
 */
@Controller
public class UiFormController {

    private final JdbcTemplate jdbc;
    private final CapabilityService caps;
    private final ItemService items;
    private final LocationService locations;
    private final EquipmentService equipment;
    private final WaveService waves;
    private final ReplenishmentService replen;

    public UiFormController(JdbcTemplate jdbc, CapabilityService caps, ItemService items,
                            LocationService locations, EquipmentService equipment, WaveService waves,
                            ReplenishmentService replen) {
        this.jdbc = jdbc; this.caps = caps; this.items = items;
        this.locations = locations; this.equipment = equipment; this.waves = waves;
        this.replen = replen;
    }

    private String back(RedirectAttributes flash, String ok, String to) {
        flash.addFlashAttribute("flash", ok);
        return "redirect:" + to;
    }

    private String fail(RedirectAttributes flash, Exception e, String to) {
        flash.addFlashAttribute("flashError", e.getMessage());
        return "redirect:" + to;
    }

    // ----------------------------- items -----------------------------

    @PostMapping("/ui/items/create")
    public String createItem(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.ITEM_MANAGE);
        String to = "/assets/items?siteId=" + f.get("siteId");
        try {
            Map<String, Object> it = new HashMap<>(f);
            var result = items.bulk(List.of(it));
            if (!result.isCompleteSuccess())
                throw ApiException.badRequest(result.errors.get(0).message());
            return back(flash, "Item " + f.get("sku") + " created.", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    @PostMapping("/ui/items/update")
    public String updateItem(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.ITEM_MANAGE);
        String to = "/assets/items/" + f.get("itemId") + "?siteId=" + f.get("siteId");
        try {
            jdbc.update("""
                UPDATE item SET description = ?, uom = COALESCE(?, uom), velocity_class = ?,
                    case_pack_qty = ?, pallet_ti = ?, pallet_hi = ?,
                    min_shelf_life_receipt_days = ?, min_shelf_life_ship_days = ?
                WHERE item_id = ?
                """, str(f.get("description")), str(f.get("uom")), str(f.get("velocityClass")),
                intVal(f.get("casePackQty"), ""), intVal(f.get("palletTi"), ""), intVal(f.get("palletHi"), ""),
                intVal(f.get("minShelfLifeReceiptDays"), ""), intVal(f.get("minShelfLifeShipDays"), ""),
                UUID.fromString(f.get("itemId")));
            return back(flash, "Item updated.", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    // ----------------------------- slots -----------------------------

    @PostMapping("/ui/slots/create")
    public String createSlot(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.LOCATION_MANAGE);
        String to = "/assets/slots?siteId=" + f.get("siteId");
        try {
            Map<String, Object> loc = new HashMap<>(f);
            loc.put("areaId", blank(f.get("areaId")) ? null : f.get("areaId"));
            var result = locations.bulk(UUID.fromString(f.get("siteId")), false, List.of(loc));
            if (!result.isCompleteSuccess())
                throw ApiException.badRequest(result.errors.get(0).message());
            return back(flash, "Slot " + f.get("code") + " created.", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    @PostMapping("/ui/slots/update")
    public String updateSlot(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.LOCATION_MANAGE);
        String to = "/assets/slots/" + f.get("locationId") + "?siteId=" + f.get("siteId");
        try {
            UUID replenItem = null;
            if (!blank(f.get("replenSku"))) {
                List<UUID> ids = jdbc.queryForList(
                    "SELECT item_id FROM item WHERE corporation_id = ? AND sku = ?::citext",
                    UUID.class, TenantContext.corp(), f.get("replenSku"));
                if (ids.isEmpty()) throw ApiException.badRequest("Unknown SKU '" + f.get("replenSku") + "'.");
                replenItem = ids.get(0);
            }
            jdbc.update("""
                UPDATE location SET width_cm = ?, depth_cm = ?, height_cm = ?, max_weight_kg = ?,
                    velocity_zone = ?, golden_zone = COALESCE(?, false),
                    replen_item_id = COALESCE(?, replen_item_id),
                    replen_min_qty = ?, replen_max_qty = ?, replen_trigger_qty = ?
                WHERE location_id = ?
                """, num(f.get("widthCm"), ""), num(f.get("depthCm"), ""), num(f.get("heightCm"), ""),
                num(f.get("maxWeightKg"), ""), str(f.get("velocityZone")), boolVal(f.get("goldenZone")),
                replenItem, num(f.get("replenMinQty"), ""), num(f.get("replenMaxQty"), ""),
                num(f.get("replenTriggerQty"), ""), UUID.fromString(f.get("locationId")));
            return back(flash, "Slot updated.", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    // ----------------------------- equipment -----------------------------

    @PostMapping("/ui/equipment/create")
    public String createEquipment(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.LOCATION_MANAGE);
        String to = "/assets/equipment?siteId=" + f.get("siteId");
        try {
            Map<String, Object> body = new HashMap<>(f);
            var result = equipment.create(body);
            UUID id = (UUID) result.get("equipmentId");
            if (!blank(f.get("capabilities"))) {
                jdbc.update("UPDATE equipment SET capabilities = ? WHERE equipment_id = ?",
                    (Object) f.get("capabilities").split("\\s*,\\s*"), id);
            }
            return back(flash, "Equipment " + f.get("code") + " registered.", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    @PostMapping("/ui/equipment/update")
    public String updateEquipment(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.LOCATION_MANAGE);
        String to = "/assets/equipment/" + f.get("equipmentId") + "?siteId=" + f.get("siteId");
        try {
            jdbc.update("""
                UPDATE equipment SET capabilities = ?, max_weight_kg = ?, check_digits = COALESCE(?, check_digits),
                    active = COALESCE(?, active)
                WHERE equipment_id = ?
                """, blank(f.get("capabilities")) ? null : (Object) f.get("capabilities").split("\\s*,\\s*"),
                num(f.get("maxWeightKg"), ""), str(f.get("checkDigits")),
                boolVal(f.get("active")), UUID.fromString(f.get("equipmentId")));
            return back(flash, "Equipment updated.", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    // ----------------------------- containers -----------------------------

    @PostMapping("/ui/containers/create")
    public String createContainer(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.LOCATION_MANAGE);
        String to = "/assets/containers?siteId=" + f.get("siteId");
        try {
            jdbc.update("""
                INSERT INTO container (corporation_id, site_id, barcode, container_type, check_digits,
                    reusable, tare_weight_kg, max_weight_kg)
                VALUES (?, ?, ?, ?::container_type, ?, COALESCE(?, true), ?, ?)
                """, TenantContext.corp(), UUID.fromString(f.get("siteId")), f.get("barcode"),
                str(f.get("containerType")), str(f.get("checkDigits")), boolVal(f.get("reusable")),
                num(f.get("tareWeightKg"), ""), num(f.get("maxWeightKg"), ""));
            return back(flash, "Container " + f.get("barcode") + " registered.", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    @PostMapping("/ui/containers/update")
    public String updateContainer(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.LOCATION_MANAGE);
        String to = "/assets/containers/" + f.get("containerId") + "?siteId=" + f.get("siteId");
        try {
            jdbc.update("""
                UPDATE container SET check_digits = COALESCE(?, check_digits), tare_weight_kg = ?,
                    max_weight_kg = ?, active = COALESCE(?, active)
                WHERE container_id = ?
                """, str(f.get("checkDigits")), num(f.get("tareWeightKg"), ""), num(f.get("maxWeightKg"), ""),
                boolVal(f.get("active")), UUID.fromString(f.get("containerId")));
            return back(flash, "Container updated.", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    // ----------------------------- actions -----------------------------

    @PostMapping("/ui/waves/create")
    public String createWave(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.WAVE_PLAN);
        String to = "/waves?siteId=" + f.get("siteId");
        try {
            Map<String, Object> result = waves.create(new HashMap<>(f));
            return back(flash, "Wave " + result.get("waveNumber") + " planned with "
                    + result.get("orders") + " orders.", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    @PostMapping("/ui/waves/release")
    public String releaseWave(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.WAVE_PLAN);
        String to = "/waves/" + f.get("waveId") + "?siteId=" + f.get("siteId");
        try {
            Map<String, Object> result = waves.release(UUID.fromString(f.get("waveId")),
                str(f.get("equipmentCode")), str(f.get("putMode")));
            return back(flash, "Released: " + ((List<?>) result.get("assignments")).size()
                    + " assignment(s), " + result.get("putMode") + " mode.", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    @PostMapping("/ui/assignments/assign")
    public String assign(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.DASHBOARD_VIEW);
        String to = "/assignments/" + f.get("assignmentId") + "?siteId=" + f.get("siteId");
        try {
            List<Map<String, Object>> user = jdbc.queryForList(
                "SELECT user_id FROM app_user WHERE email = ?::citext AND active", f.get("userEmail"));
            if (user.isEmpty()) throw ApiException.notFound("No active user with that email.");
            UUID userId = (UUID) user.get(0).get("user_id");
            int n = jdbc.update("""
                UPDATE assignment SET
                    previous_assignee = CASE WHEN assigned_to IS NOT NULL AND assigned_to <> ? THEN assigned_to
                                             ELSE previous_assignee END,
                    reassigned_count  = reassigned_count + CASE WHEN assigned_to IS NOT NULL AND assigned_to <> ? THEN 1 ELSE 0 END,
                    assigned_to = ?, last_assigned_at = now(),
                    status = CASE WHEN status = 'OPEN' THEN 'ASSIGNED' ELSE status END,
                    started_at = COALESCE(started_at, now())
                WHERE assignment_id = ? AND status NOT IN ('COMPLETE','CANCELLED')
                """, userId, userId, userId, UUID.fromString(f.get("assignmentId")));
            if (n == 0) throw ApiException.conflict("Assignment is complete or cancelled.");
            return back(flash, "Assigned to " + f.get("userEmail") + ".", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    @PostMapping("/ui/assignments/attach-container")
    public String attachContainer(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.SELECTION_EXECUTE);
        String to = "/assignments/" + f.get("assignmentId") + "?siteId=" + f.get("siteId");
        try {
            List<UUID> cid = jdbc.queryForList("""
                SELECT c.container_id FROM container c
                JOIN assignment a ON a.assignment_id = ? AND a.site_id = c.site_id
                WHERE c.barcode = ? AND c.active
                """, UUID.class, UUID.fromString(f.get("assignmentId")), f.get("barcode"));
            if (cid.isEmpty()) throw ApiException.notFound("No active container '" + f.get("barcode") + "'.");
            int n = jdbc.update("""
                UPDATE assignment_container SET container_id = ?
                WHERE assignment_id = ? AND cart_position = ?
                """, cid.get(0), UUID.fromString(f.get("assignmentId")),
                Integer.parseInt(f.get("cartPosition")));
            if (n == 0) throw ApiException.conflict("No such cart position on this assignment.");
            return back(flash, "Tote " + f.get("barcode") + " on position " + f.get("cartPosition") + ".", to);
        } catch (Exception e) { return fail(flash, e, to); }
    }

    @PostMapping("/ui/replen/scan")
    public String replenScan(@RequestParam UUID siteId, RedirectAttributes flash) {
        caps.require(TenantContext.user(), siteId, Capabilities.REPLEN_EXECUTE);
        try {
            Map<String, Object> r = replen.scan(siteId);
            return back(flash, "Replen scan: " + ((List<?>) r.get("assignmentsCreated")).size()
                    + " assignment(s), " + r.get("criticalAlerts") + " critical alert(s).", "/?siteId=" + siteId);
        } catch (Exception e) { return fail(flash, e, "/?siteId=" + siteId); }
    }
}
