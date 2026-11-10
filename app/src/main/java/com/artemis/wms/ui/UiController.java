package com.artemis.wms.ui;

import com.artemis.wms.alerts.AlertService;
import com.artemis.wms.identity.UserService;
import com.artemis.wms.inbound.ReceivingService;
import com.artemis.wms.masterdata.CustomerService;
import com.artemis.wms.masterdata.ItemService;
import com.artemis.wms.org.OrgService;
import com.artemis.wms.org.PolicyService;
import com.artemis.wms.outbound.OrderService;
import com.artemis.wms.outbound.SelectionService;
import com.artemis.wms.outbound.ShippingService;
import com.artemis.wms.outbound.WaveService;
import com.artemis.wms.replen.ReplenService;
import com.artemis.wms.tenancy.TenantContext;
import com.artemis.wms.work.DispatchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.platform.Values.str;
import static com.artemis.wms.platform.Values.upper;

/**
 * Server-rendered pages. Reads only; every write goes through {@link UiActionController}.
 * Documents (orders, waves, assignments, manifests, shipments) answer to their
 * printed number as well as their id, and every physical thing has a page
 * under its own name — see {@link Links} — so any screen can link anywhere.
 */
@Controller
public class UiController {
    private final UiQueries q; private final Lookup look; private final SearchService search;
    private final OrgService org; private final PolicyService policy;
    private final ItemService items; private final CustomerService customers;
    private final ReceivingService receiving;
    private final OrderService orders; private final WaveService waves; private final SelectionService selection; private final ShippingService shipping;
    private final ReplenService replen; private final AlertService alerts; private final DispatchService dispatch; private final com.artemis.wms.work.WorkService work;

    public UiController(UiQueries q, Lookup look, SearchService search, OrgService org, PolicyService policy, ItemService items, CustomerService customers,
                        ReceivingService receiving, OrderService orders, WaveService waves, SelectionService selection,
                        ShippingService shipping, ReplenService replen, AlertService alerts, DispatchService dispatch, com.artemis.wms.work.WorkService work) {
        this.q = q; this.look = look; this.search = search; this.org = org; this.policy = policy; this.items = items; this.customers = customers;
        this.receiving = receiving; this.orders = orders; this.waves = waves; this.selection = selection;
        this.shipping = shipping; this.replen = replen; this.alerts = alerts; this.dispatch = dispatch; this.work = work;
    }

    private static UUID site(Model m) { return (UUID) m.getAttribute("siteId"); }
    /** Pin the page to the site its subject lives at, and hand the template that site. */
    private void at(HttpServletRequest req, Model m, Object siteId) {
        UiAdvice.pin(req, m, siteId);
        if (siteId instanceof UUID id) m.addAttribute("site", q.site(id));
    }

    @GetMapping("/login") public String login() { return "login"; }

    @GetMapping("/")
    public String home(Model m) {
        if (TenantContext.sysadmin() && TenantContext.corp() == null) { m.addAttribute("corporations", org.corporations()); return "platform"; }
        List<Map<String, Object>> sites = org.sites();
        if (sites.size() == 1) return "redirect:/sites/" + sites.get(0).get("site_id") + "/board";
        return "home";
    }

    // ---- the floor
    @GetMapping("/sites/{siteId}/board")
    public String board(@PathVariable UUID siteId, @RequestParam(required = false) String zone, Model m) {
        String z = upper(zone);
        m.addAttribute("site", q.site(siteId)); m.addAttribute("zone", z);
        m.addAttribute("counts", q.boardCounts(siteId));
        m.addAttribute("manifests", q.openManifests(siteId));
        m.addAttribute("putaway", q.openAssignments(siteId, "PUTAWAY", z));
        m.addAttribute("replen", q.openAssignments(siteId, "REPLENISHMENT", z));
        m.addAttribute("selection", q.openAssignments(siteId, "SELECTION", z));
        m.addAttribute("unbuilt", waves.unbuilt(siteId, z)); m.addAttribute("units", look.fleet(siteId)); m.addAttribute("myWork", work.allowed(siteId));
        m.addAttribute("shipping", q.readyToShip(siteId));
        m.addAttribute("alerts", alerts.open(siteId));
        m.addAttribute("eligible", Map.of("PUTAWAY", dispatch.eligible(siteId, "PUTAWAY"), "SELECTION", dispatch.eligible(siteId, "SELECTION"), "REPLENISHMENT", dispatch.eligible(siteId, "REPLENISHMENT")));
        return "board";
    }

    @GetMapping("/sites/{siteId}/racking")
    public String racking(@PathVariable UUID siteId, @RequestParam(required = false) String zone,
                          @RequestParam(required = false) String aisle, @RequestParam(required = false) String view, Model m) {
        m.addAttribute("site", q.site(siteId));
        List<Map<String, Object>> zones = q.locationZones(siteId);
        List<Map<String, Object>> docks = q.docks(siteId);
        m.addAttribute("zones", zones);
        m.addAttribute("inbound", docks.stream().filter(d -> "INBOUND".equals(d.get("flow"))).toList());
        m.addAttribute("outbound", docks.stream().filter(d -> "OUTBOUND".equals(d.get("flow"))).toList());
        String v = upper(view), zn = upper(zone);
        m.addAttribute("view", "INBOUND".equals(v) || "OUTBOUND".equals(v) ? v : null);
        m.addAttribute("aisle", upper(aisle));
        if (zn != null) {
            Map<String, Object> z = zones.stream().filter(r -> zn.equals(r.get("zone"))).findFirst()
                .orElseThrow(() -> com.artemis.wms.platform.ApiException.notFound("No zone " + zn + " at this site."));
            m.addAttribute("here", z);
            m.addAttribute("aisles", q.locationAisles(siteId, zn));
            // One aisle at a time once a zone is too big to draw whole.
            boolean small = ((Number) z.get("slots")).intValue() <= 600;
            if (aisle != null || small) m.addAttribute("racks", q.racking(siteId, zn, upper(aisle)));
        }
        return "racking";
    }

    @GetMapping("/slots/{code}")
    public String slot(@PathVariable String code, HttpServletRequest req, Model m) {
        Map<String, Object> s = look.slot(code, site(m)); m.addAttribute("slot", s); at(req, m, s.get("site_id")); return "slot";
    }

    @GetMapping("/sites/{siteId}/pallets")
    public String pallets(@PathVariable UUID siteId, @RequestParam(required = false) String q, @RequestParam(required = false) String status,
                          @RequestParam(required = false) String zone, @RequestParam(required = false) String where, Model m) {
        m.addAttribute("site", this.q.site(siteId)); m.addAttribute("q", q); m.addAttribute("status", upper(status)); m.addAttribute("zone", upper(zone)); m.addAttribute("where", upper(where));
        m.addAttribute("pallets", look.pallets(siteId, str(q), upper(status), upper(zone), upper(where)));
        return "pallets";
    }
    @GetMapping("/pallets/{lpn}")
    public String pallet(@PathVariable String lpn, HttpServletRequest req, Model m) {
        Map<String, Object> p = look.pallet(lpn, site(m)); m.addAttribute("pallet", p); at(req, m, p.get("site_id")); return "pallet";
    }
    /** The old LPN lookup address; search is the one box now. */
    @GetMapping("/lpn") public String lpn(@RequestParam(required = false) String q) { return "redirect:" + new Links().search(q == null ? "" : q); }

    @GetMapping("/sites/{siteId}/lots")
    public String lots(@PathVariable UUID siteId, @RequestParam(required = false) String q, Model m) {
        m.addAttribute("site", this.q.site(siteId)); m.addAttribute("q", q); m.addAttribute("lots", look.lots(siteId, str(q))); return "lots";
    }
    @GetMapping("/lots/{sku}")
    public String lot(@PathVariable String sku, @RequestParam String lot, Model m) { m.addAttribute("lot", look.lot(sku, lot)); return "lot"; }

    @GetMapping("/search")
    public String search(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean stay, Model m) {
        List<SearchService.Hit> hits = search.search(q);
        var only = search.only(hits);
        if (only.isPresent() && !stay) return "redirect:" + only.get().url();
        m.addAttribute("q", q == null ? "" : q.trim()); m.addAttribute("hits", hits);
        m.addAttribute("kinds", hits.stream().map(SearchService.Hit::kind).distinct().toList());
        return "search";
    }

    // ---- inbound
    @GetMapping("/sites/{siteId}/receiving")
    public String receivingList(@PathVariable UUID siteId, @RequestParam(defaultValue = "true") boolean open, Model m) {
        m.addAttribute("site", q.site(siteId)); m.addAttribute("manifests", receiving.list(siteId, open)); m.addAttribute("open", open); return "receiving";
    }
    @GetMapping("/receiving/{key}")
    public String manifest(@PathVariable String key, HttpServletRequest req, Model m) {
        Map<String, Object> man = receiving.get(look.resolve("manifest", key, site(m))); m.addAttribute("manifest", man); at(req, m, man.get("site_id"));
        m.addAttribute("docks", q.docks((UUID) man.get("site_id"))); return "manifest";
    }

    // ---- outbound
    @GetMapping("/sites/{siteId}/orders")
    public String ordersList(@PathVariable UUID siteId, @RequestParam(required = false) String status, @RequestParam(required = false) String customer,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, Model m) {
        m.addAttribute("site", q.site(siteId)); m.addAttribute("orders", q.orders(siteId, upper(status), str(customer), from, to));
        m.addAttribute("status", upper(status)); m.addAttribute("customer", customer); m.addAttribute("from", from); m.addAttribute("to", to);
        m.addAttribute("customers", customers.list(null)); return "orders";
    }
    @GetMapping("/orders/{key}")
    public String order(@PathVariable String key, HttpServletRequest req, Model m) {
        Map<String, Object> o = orders.get(look.resolve("order", key, site(m))); m.addAttribute("order", o); at(req, m, o.get("site_id")); return "order";
    }
    @GetMapping("/sites/{siteId}/waves")
    public String wavesList(@PathVariable UUID siteId, Model m) {
        m.addAttribute("site", q.site(siteId)); m.addAttribute("waves", waves.list(siteId)); m.addAttribute("openOrders", q.unwavedZoneOrders(siteId)); return "waves";
    }
    @GetMapping("/waves/{key}")
    public String wave(@PathVariable String key, HttpServletRequest req, Model m) {
        Map<String, Object> w = waves.get(look.resolve("wave", key, site(m))); m.addAttribute("wave", w); at(req, m, w.get("site_id"));
        m.addAttribute("equipment", look.fleet((UUID) w.get("site_id"))); return "wave";
    }
    @GetMapping("/assignments/{key}")
    public String assignment(@PathVariable String key, HttpServletRequest req, Model m) {
        Map<String, Object> a = selection.get(look.resolve("assignment", key, site(m))); m.addAttribute("a", a); UUID siteId = (UUID) a.get("site_id");
        at(req, m, siteId); m.addAttribute("eligible", dispatch.eligible(siteId, (String) a.get("assignment_type")));
        m.addAttribute("dropLocations", q.docks(siteId)); m.addAttribute("units", look.fleet(siteId)); return "assignment";
    }
    @GetMapping("/sites/{siteId}/shipments")
    public String shipmentsList(@PathVariable UUID siteId, Model m) {
        m.addAttribute("site", q.site(siteId)); m.addAttribute("shipments", shipping.list(siteId));
        m.addAttribute("ready", q.readyToShip(siteId)); m.addAttribute("docks", q.docks(siteId)); return "shipments";
    }
    @GetMapping("/shipments/{key}")
    public String shipment(@PathVariable String key, HttpServletRequest req, Model m) {
        Map<String, Object> s = shipping.get(look.resolve("shipment", key, site(m))); m.addAttribute("shipment", s); at(req, m, s.get("site_id")); return "shipment";
    }

    // ---- insight
    @GetMapping("/sites/{siteId}/dashboard")
    public String dashboard(@PathVariable UUID siteId, Model m) {
        m.addAttribute("site", q.site(siteId)); m.addAttribute("d", q.dashboard(siteId)); m.addAttribute("pressure", replen.pressure(siteId)); return "dashboard";
    }
    @GetMapping("/sites/{siteId}/alerts")
    public String alertsPage(@PathVariable UUID siteId, Model m) { m.addAttribute("site", q.site(siteId)); m.addAttribute("alerts", alerts.open(siteId)); return "alerts"; }
    @GetMapping("/notifications")
    public String notifications(Model m) { m.addAttribute("notifications", alerts.notifications(false)); return "notifications"; }

    // ---- master data
    @GetMapping("/items")
    public String itemsPage(@RequestParam(required = false) String q, @RequestParam(required = false) String zone, @RequestParam(defaultValue = "1") int page, Model m) {
        int total = look.itemCount(str(q), upper(zone)), pages = Math.max(1, (total + Lookup.ITEM_PAGE - 1) / Lookup.ITEM_PAGE), p = Math.min(Math.max(1, page), pages);
        m.addAttribute("items", look.items(str(q), upper(zone), p)); m.addAttribute("q", q); m.addAttribute("zone", upper(zone));
        m.addAttribute("total", total); m.addAttribute("page", p); m.addAttribute("pages", pages);
        return "items";
    }
    @GetMapping("/items/{sku}")
    public String item(@PathVariable String sku, Model m) {
        Map<String, Object> it = items.get(sku); m.addAttribute("item", it); m.addAttribute("ctx", look.itemContext((UUID) it.get("item_id"))); return "item";
    }
    @GetMapping("/customers")
    public String customersPage(@RequestParam(required = false) String q, Model m) { m.addAttribute("customers", customers.list(str(q))); m.addAttribute("q", q); return "customers"; }
    @GetMapping("/customers/{code}")
    public String customer(@PathVariable String code, Model m) {
        Map<String, Object> c = customers.get(code); m.addAttribute("customer", c); m.addAttribute("ctx", look.customerContext((UUID) c.get("customer_id"))); return "customer";
    }

    // ---- people and equipment
    @GetMapping("/operators")
    public String operators(Model m) {
        UUID corp = TenantContext.corp();
        if (corp == null) return "redirect:/";
        m.addAttribute("operators", look.operators(corp)); m.addAttribute("tree", q.orgTree(corp)); m.addAttribute("workflows", UserService.WORKFLOWS); return "operators";
    }
    @GetMapping("/operators/{id}")
    public String operator(@PathVariable UUID id, Model m) { m.addAttribute("op", look.operator(id)); m.addAttribute("workflows", UserService.WORKFLOWS); return "operator"; }
    @GetMapping("/users") public String users() { return "redirect:/operators"; }

    /** Notifications written by the database carry this older address. */
    @GetMapping("/alerts") public String alertsLegacy(@RequestParam UUID siteId) { return "redirect:/sites/" + siteId + "/alerts"; }

    @GetMapping("/sites/{siteId}/equipment")
    public String fleet(@PathVariable UUID siteId, Model m) {
        m.addAttribute("site", q.site(siteId)); m.addAttribute("fleet", look.fleet(siteId)); m.addAttribute("people", q.operators(siteId)); return "fleet";
    }
    @GetMapping("/equipment/{code}")
    public String equipment(@PathVariable String code, HttpServletRequest req, Model m) {
        Map<String, Object> e = look.equipment(code, site(m)); m.addAttribute("unit", e); at(req, m, e.get("site_id"));
        m.addAttribute("people", q.operators((UUID) e.get("site_id"))); return "equipment";
    }

    // ---- organization
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
}
