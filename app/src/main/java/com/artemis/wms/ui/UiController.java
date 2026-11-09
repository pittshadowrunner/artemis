package com.artemis.wms.ui;

import com.artemis.wms.alerts.AlertService;
import com.artemis.wms.identity.UserService;
import com.artemis.wms.inbound.ReceivingService;
import com.artemis.wms.inventory.InventoryService;
import com.artemis.wms.masterdata.CustomerService;
import com.artemis.wms.masterdata.EquipmentService;
import com.artemis.wms.masterdata.ItemService;
import com.artemis.wms.org.OrgService;
import com.artemis.wms.org.PolicyService;
import com.artemis.wms.outbound.OrderService;
import com.artemis.wms.outbound.SelectionService;
import com.artemis.wms.outbound.ShippingService;
import com.artemis.wms.outbound.WaveService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.replen.ReplenService;
import com.artemis.wms.tenancy.TenantContext;
import com.artemis.wms.work.DispatchService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-rendered pages. Reads only; every write goes through {@link UiActionController}. */
@Controller
public class UiController {
    private final UiQueries q;
    private final OrgService org; private final PolicyService policy; private final UserService users;
    private final ItemService items; private final CustomerService customers; private final EquipmentService equipment;
    private final InventoryService inventory; private final ReceivingService receiving;
    private final OrderService orders; private final WaveService waves; private final SelectionService selection; private final ShippingService shipping;
    private final ReplenService replen; private final AlertService alerts; private final DispatchService dispatch;

    public UiController(UiQueries q, OrgService org, PolicyService policy, UserService users, ItemService items, CustomerService customers, EquipmentService equipment,
                        InventoryService inventory, ReceivingService receiving, OrderService orders, WaveService waves, SelectionService selection,
                        ShippingService shipping, ReplenService replen, AlertService alerts, DispatchService dispatch) {
        this.q = q; this.org = org; this.policy = policy; this.users = users; this.items = items; this.customers = customers; this.equipment = equipment;
        this.inventory = inventory; this.receiving = receiving; this.orders = orders; this.waves = waves; this.selection = selection;
        this.shipping = shipping; this.replen = replen; this.alerts = alerts; this.dispatch = dispatch;
    }

    @GetMapping("/login") public String login() { return "login"; }

    @GetMapping("/")
    public String home(Model m) {
        if (TenantContext.sysadmin() && TenantContext.corp() == null) { m.addAttribute("corporations", org.corporations()); return "platform"; }
        List<Map<String, Object>> sites = org.sites();
        if (sites.size() == 1) return "redirect:/sites/" + sites.get(0).get("site_id") + "/board";
        return "home";
    }

    @GetMapping("/sites/{siteId}/board")
    public String board(@PathVariable UUID siteId, Model m) {
        m.addAttribute("site", q.site(siteId));
        m.addAttribute("counts", q.boardCounts(siteId));
        m.addAttribute("manifests", receiving.list(siteId, true));
        m.addAttribute("putaway", q.openAssignments(siteId, "PUTAWAY"));
        m.addAttribute("selection", q.openAssignments(siteId, "SELECTION"));
        m.addAttribute("replen", q.openAssignments(siteId, "REPLENISHMENT"));
        m.addAttribute("pressure", replen.pressure(siteId));
        m.addAttribute("alerts", alerts.open(siteId));
        m.addAttribute("eligible", Map.of("PUTAWAY", dispatch.eligible(siteId, "PUTAWAY"), "SELECTION", dispatch.eligible(siteId, "SELECTION"), "REPLENISHMENT", dispatch.eligible(siteId, "REPLENISHMENT")));
        return "board";
    }

    @GetMapping("/sites/{siteId}/racking")
    public String racking(@PathVariable UUID siteId, Model m) {
        m.addAttribute("site", q.site(siteId)); m.addAttribute("zones", q.racking(siteId)); m.addAttribute("docks", q.docks(siteId));
        return "racking";
    }


    @GetMapping("/lpn")
    public String lpn(@RequestParam(required = false) String q, Model m) {
        if (q != null && !q.isBlank()) {
            try { m.addAttribute("pallet", inventory.pallet(q.trim())); }
            catch (ApiException e) { m.addAttribute("error", e.getMessage()); }
        }
        m.addAttribute("q", q);
        return "lpn";
    }

    // ---- inbound
    @GetMapping("/sites/{siteId}/receiving")
    public String receivingList(@PathVariable UUID siteId, @RequestParam(defaultValue = "true") boolean open, Model m) {
        m.addAttribute("site", q.site(siteId)); m.addAttribute("manifests", receiving.list(siteId, open)); m.addAttribute("open", open); return "receiving";
    }
    @GetMapping("/receiving/{id}")
    public String manifest(@PathVariable UUID id, Model m) {
        Map<String, Object> man = receiving.get(id); m.addAttribute("manifest", man); m.addAttribute("site", q.site((UUID) man.get("site_id"))); return "manifest";
    }

    // ---- outbound
    @GetMapping("/sites/{siteId}/orders")
    public String ordersList(@PathVariable UUID siteId, @RequestParam(required = false) String status, Model m) {
        m.addAttribute("site", q.site(siteId)); m.addAttribute("orders", orders.list(siteId, status == null ? null : status.toUpperCase())); m.addAttribute("status", status);
        m.addAttribute("customers", customers.list(null)); return "orders";
    }
    @GetMapping("/orders/{id}")
    public String order(@PathVariable UUID id, Model m) {
        Map<String, Object> o = orders.get(id); m.addAttribute("order", o); m.addAttribute("site", q.site((UUID) o.get("site_id"))); return "order";
    }
    @GetMapping("/sites/{siteId}/waves")
    public String wavesList(@PathVariable UUID siteId, Model m) {
        m.addAttribute("site", q.site(siteId)); m.addAttribute("waves", waves.list(siteId)); m.addAttribute("equipment", equipment.list(siteId));
        m.addAttribute("openZones", orders.list(siteId, null)); return "waves";
    }
    @GetMapping("/waves/{id}")
    public String wave(@PathVariable UUID id, Model m) {
        Map<String, Object> w = waves.get(id); m.addAttribute("wave", w); m.addAttribute("site", q.site((UUID) w.get("site_id")));
        m.addAttribute("equipment", equipment.list((UUID) w.get("site_id"))); return "wave";
    }
    @GetMapping("/assignments/{id}")
    public String assignment(@PathVariable UUID id, Model m) {
        Map<String, Object> a = selection.get(id); m.addAttribute("a", a); UUID siteId = (UUID) a.get("site_id");
        m.addAttribute("site", q.site(siteId)); m.addAttribute("eligible", dispatch.eligible(siteId, (String) a.get("assignment_type")));
        m.addAttribute("dropLocations", q.docks(siteId)); return "assignment";
    }
    @GetMapping("/sites/{siteId}/shipments")
    public String shipmentsList(@PathVariable UUID siteId, Model m) {
        m.addAttribute("site", q.site(siteId)); m.addAttribute("shipments", shipping.list(siteId));
        m.addAttribute("ready", orders.list(siteId, "DROPPED")); m.addAttribute("docks", q.docks(siteId)); return "shipments";
    }
    @GetMapping("/shipments/{id}")
    public String shipment(@PathVariable UUID id, Model m) {
        Map<String, Object> s = shipping.get(id); m.addAttribute("shipment", s); m.addAttribute("site", q.site((UUID) s.get("site_id"))); return "shipment";
    }

    // ---- insight
    @GetMapping("/sites/{siteId}/dashboard")
    public String dashboard(@PathVariable UUID siteId, Model m) { m.addAttribute("site", q.site(siteId)); m.addAttribute("d", q.dashboard(siteId)); return "dashboard"; }
    @GetMapping("/sites/{siteId}/alerts")
    public String alertsPage(@PathVariable UUID siteId, Model m) { m.addAttribute("site", q.site(siteId)); m.addAttribute("alerts", alerts.open(siteId)); return "alerts"; }
    @GetMapping("/notifications")
    public String notifications(Model m) { m.addAttribute("notifications", alerts.notifications(false)); return "notifications"; }

    // ---- master data & admin
    @GetMapping("/items")
    public String itemsPage(@RequestParam(required = false) String q, Model m) { m.addAttribute("items", items.list(q, null)); m.addAttribute("q", q); return "items"; }
    @GetMapping("/items/{sku}")
    public String item(@PathVariable String sku, Model m) { m.addAttribute("item", items.get(sku)); return "item"; }
    @GetMapping("/customers")
    public String customersPage(@RequestParam(required = false) String q, Model m) { m.addAttribute("customers", customers.list(q)); m.addAttribute("q", q); return "customers"; }
    @GetMapping("/org")
    public String orgPage(Model m) {
        UUID corp = TenantContext.corp();
        if (corp == null) return "redirect:/";
        m.addAttribute("tree", q.orgTree(corp)); return "org";
    }
    @GetMapping("/org/{id}/policy")
    public String policyPage(@PathVariable UUID id, Model m) {
        m.addAttribute("node", org.node(id)); m.addAttribute("declared", policy.declared(id)); m.addAttribute("effective", policy.effective(id)); return "policy";
    }
    @GetMapping("/users")
    public String usersPage(Model m) {
        UUID corp = TenantContext.corp();
        if (corp == null) return "redirect:/";
        m.addAttribute("users", users.operators(corp)); m.addAttribute("tree", q.orgTree(corp)); m.addAttribute("workflows", UserService.WORKFLOWS); return "users";
    }
}
