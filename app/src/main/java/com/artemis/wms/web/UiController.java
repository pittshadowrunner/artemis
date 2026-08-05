package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.UiService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-rendered operator UI (BUILD_PLAN decision 2: Thymeleaf for beta).
 * The pages read the same views the REST metrics endpoints expose — the UI
 * is just another client. Dashboard pages require DASHBOARD_VIEW, matching
 * the admin-only dashboard rule.
 */
@Controller
public class UiController {

    private final UiService ui;
    private final CapabilityService caps;

    public UiController(UiService ui, CapabilityService caps) { this.ui = ui; this.caps = caps; }

    private UUID resolveSite(UUID siteId, Model model) {
        List<Map<String, Object>> sites = ui.sites();
        model.addAttribute("sites", sites);
        if (siteId == null && !sites.isEmpty()) siteId = (UUID) sites.get(0).get("org_node_id");
        if (siteId != null) {
            model.addAttribute("siteId", siteId);
            model.addAttribute("crumb", ui.breadcrumb(siteId));
        }
        model.addAttribute("userEmail", currentEmail());
        model.addAttribute("sysadmin", caps.isSysadmin(TenantContext.user()));
        model.addAttribute("asOf", LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d · HH:mm")));
        return siteId;
    }

    private String currentEmail() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? "" : auth.getName();
    }

    @GetMapping("/")
    public String operations(@RequestParam(required = false) UUID siteId, Model model) {
        caps.require(TenantContext.user(), null, Capabilities.DASHBOARD_VIEW);
        UUID site = resolveSite(siteId, model);
        if (site == null) return "empty";
        model.addAttribute("wave", ui.activeWave(site));
        model.addAttribute("lanes", ui.lanes(site));
        model.addAttribute("tasks", ui.openTasks(site, 20));
        model.addAttribute("expiring", ui.expiringLots(site, 6));
        model.addAttribute("zones", ui.zoneOccupancy(site));
        return "ops";
    }

    @GetMapping("/metrics")
    public String metrics(@RequestParam(required = false) UUID siteId, Model model) {
        caps.require(TenantContext.user(), null, Capabilities.DASHBOARD_VIEW);
        UUID site = resolveSite(siteId, model);
        if (site == null) return "empty";
        model.addAttribute("kpis", ui.kpis(site));
        model.addAttribute("velocity", ui.velocity(site, 8));
        model.addAttribute("waves", ui.waveBoard(site));
        model.addAttribute("receiving", ui.receivingToday(site));
        model.addAttribute("pipeline", ui.shippingPipeline(site));
        model.addAttribute("labor", ui.laborSelection(site));
        return "metrics";
    }
}
