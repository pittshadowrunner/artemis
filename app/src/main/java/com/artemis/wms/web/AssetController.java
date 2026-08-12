package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.AssetService;
import com.artemis.wms.service.UiService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asset screens. One navigation grammar everywhere: list pages take
 * ?siteId, detail pages take the asset id; every screen links up via
 * breadcrumb chips and down via linked rows. All DASHBOARD_VIEW-gated,
 * same as the ops board they hang off.
 */
@Controller
public class AssetController {

    private final AssetService assets;
    private final UiService ui;
    private final CapabilityService caps;

    public AssetController(AssetService assets, UiService ui, CapabilityService caps) {
        this.assets = assets; this.ui = ui; this.caps = caps;
    }

    private UUID site(UUID siteId, Model model) {
        caps.require(TenantContext.user(), null, Capabilities.DASHBOARD_VIEW);
        List<Map<String, Object>> sites = ui.sites();
        if (siteId == null && !sites.isEmpty()) siteId = (UUID) sites.get(0).get("org_node_id");
        model.addAttribute("siteId", siteId);
        if (siteId != null) model.addAttribute("crumb", ui.breadcrumb(siteId));
        model.addAttribute("userEmail", org.springframework.security.core.context
                .SecurityContextHolder.getContext().getAuthentication().getName());
        model.addAttribute("sysadmin", caps.isSysadmin(TenantContext.user()));
        return siteId;
    }

    @GetMapping("/assets")
    public String hub(@RequestParam(required = false) UUID siteId, Model model) {
        UUID s = site(siteId, model);
        model.addAttribute("counts", assets.hubCounts(s));
        return "assets/hub";
    }

    @GetMapping("/assets/items")
    public String items(@RequestParam(required = false) UUID siteId, Model model) {
        UUID s = site(siteId, model);
        model.addAttribute("items", assets.items(s));
        return "assets/items";
    }

    @GetMapping("/assets/items/{id}")
    public String item(@PathVariable UUID id, @RequestParam(required = false) UUID siteId, Model model) {
        UUID s = site(siteId, model);
        model.addAttribute("it", assets.item(id, s));
        return "assets/item";
    }

    @GetMapping("/assets/slots")
    public String slots(@RequestParam(required = false) UUID siteId,
                        @RequestParam(required = false) UUID zoneId, Model model) {
        UUID s = site(siteId, model);
        model.addAttribute("slots", assets.slots(s, zoneId));
        model.addAttribute("zone", zoneId == null ? null : assets.zone(zoneId));
        model.addAttribute("zones", assets.zones(s));
        return "assets/slots";
    }

    @GetMapping("/assets/slots/{id}")
    public String slot(@PathVariable UUID id, @RequestParam(required = false) UUID siteId, Model model) {
        site(siteId, model);
        model.addAttribute("s", assets.slot(id));
        return "assets/slot";
    }

    @GetMapping("/assets/zones")
    public String zones(@RequestParam(required = false) UUID siteId, Model model) {
        UUID s = site(siteId, model);
        model.addAttribute("zones", assets.zones(s));
        return "assets/zones";
    }

    @GetMapping("/assets/equipment")
    public String equipmentList(@RequestParam(required = false) UUID siteId, Model model) {
        UUID s = site(siteId, model);
        model.addAttribute("equipment", assets.equipmentList(s));
        return "assets/equipment_list";
    }

    @GetMapping("/assets/equipment/{id}")
    public String equipment(@PathVariable UUID id, @RequestParam(required = false) UUID siteId, Model model) {
        site(siteId, model);
        model.addAttribute("e", assets.equipment(id));
        return "assets/equipment";
    }

    @GetMapping("/assets/containers")
    public String containers(@RequestParam(required = false) UUID siteId, Model model) {
        UUID s = site(siteId, model);
        model.addAttribute("containers", assets.containers(s));
        return "assets/containers";
    }

    @GetMapping("/assets/containers/{id}")
    public String container(@PathVariable UUID id, @RequestParam(required = false) UUID siteId, Model model) {
        site(siteId, model);
        model.addAttribute("c", assets.container(id));
        return "assets/container";
    }

    /** Receiving detail: the manifest as a linkable document — what came,
     *  when it finished, and the LPNs it produced (inventory keeps the
     *  received_from_manifest link, so the pallet trail is native). */
    @GetMapping("/receiving/{id}")
    public String receiving(@PathVariable UUID id, @RequestParam(required = false) UUID siteId, Model model) {
        site(siteId, model);
        model.addAttribute("m", assets.manifest(id));
        return "receiving";
    }

    /** Pallet lookup by LPN: the receiving output is LPN + captured
     *  attributes + a warehouse location; this makes it findable. */
    @GetMapping("/lpn")
    public String lpn(@RequestParam(required = false) String q,
                      @RequestParam(required = false) UUID siteId, Model model) {
        UUID s = site(siteId, model);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("hits", q == null || q.isBlank()
                ? java.util.List.of() : assets.lpnSearch(s, q.trim()));
        return "lpn";
    }

    @GetMapping("/waves")
    public String waves(@RequestParam(required = false) UUID siteId, Model model) {
        UUID s = site(siteId, model);
        model.addAttribute("waves", assets.waves(s));
        model.addAttribute("fleet", assets.equipmentList(s));
        return "assets/waves";
    }

    @GetMapping("/waves/{id}")
    public String wave(@PathVariable UUID id, @RequestParam(required = false) UUID siteId, Model model) {
        site(siteId, model);
        Map<String, Object> w = assets.wave(id);
        model.addAttribute("w", w);
        model.addAttribute("fleet", assets.equipmentList((UUID) w.get("site_id")));
        return "assets/wave";
    }

    @GetMapping("/assignments/{id}")
    public String assignment(@PathVariable UUID id, @RequestParam(required = false) UUID siteId, Model model) {
        site(siteId, model);
        Map<String, Object> a = assets.assignment(id);
        model.addAttribute("a", a);
        // max qty for the pick-line graph scale
        java.math.BigDecimal max = java.math.BigDecimal.ONE;
        for (Map<String, Object> l : (List<Map<String, Object>>) a.get("lines")) {
            java.math.BigDecimal q = (java.math.BigDecimal) l.get("qty");
            if (q != null && q.compareTo(max) > 0) max = q;
        }
        model.addAttribute("maxQty", max);
        model.addAttribute("freeTotes", assets.freeContainers((UUID) a.get("site_id")));
        return "assets/assignment";
    }
}
