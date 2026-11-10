package com.artemis.wms.ui;

import com.artemis.wms.alerts.AlertService;
import com.artemis.wms.identity.UserService;
import com.artemis.wms.inbound.PutawayService;
import com.artemis.wms.inbound.ReceivingService;
import com.artemis.wms.masterdata.EquipmentService;
import com.artemis.wms.org.OrgService;
import com.artemis.wms.tenancy.TenantFilter;
import jakarta.servlet.http.HttpServletRequest;
import com.artemis.wms.org.PolicyService;
import com.artemis.wms.outbound.OrderService;
import com.artemis.wms.outbound.SelectionService;
import com.artemis.wms.outbound.ShippingService;
import com.artemis.wms.outbound.WaveService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.replen.ReplenService;
import com.artemis.wms.tenancy.TenantContext;
import com.artemis.wms.work.DispatchService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

import static com.artemis.wms.platform.Values.*;

/**
 * Form posts. Each one calls the same service the API calls, flashes the
 * outcome, and redirects back — so the UI can never do something the API
 * can't, and a domain rejection reads as a plain sentence on the page.
 */
@Controller
@RequestMapping("/ui")
public class UiActionController {
    private final ReceivingService receiving; private final PutawayService putaway; private final DispatchService dispatch;
    private final OrderService orders; private final WaveService waves; private final SelectionService selection; private final ShippingService shipping;
    private final ReplenService replen; private final AlertService alerts; private final PolicyService policy; private final UserService users;
    private final EquipmentService equipment; private final OrgService org; private final com.artemis.wms.work.WorkService work;

    public UiActionController(ReceivingService receiving, PutawayService putaway, DispatchService dispatch, OrderService orders, WaveService waves,
                              SelectionService selection, ShippingService shipping, ReplenService replen, AlertService alerts, PolicyService policy, UserService users,
                              EquipmentService equipment, OrgService org, com.artemis.wms.work.WorkService work) {
        this.receiving = receiving; this.putaway = putaway; this.dispatch = dispatch; this.orders = orders; this.waves = waves; this.selection = selection;
        this.shipping = shipping; this.replen = replen; this.alerts = alerts; this.policy = policy; this.users = users;
        this.equipment = equipment; this.org = org; this.work = work;
    }

    /** Only ever send someone back to a page of ours. */
    private static String local(String back, String fallback) { return back != null && back.startsWith("/") && !back.startsWith("//") ? back : fallback; }

    /** For actions whose destination depends on what they made. */
    private String go(RedirectAttributes ra, String[] back, Runnable action, String okMessage) {
        go(ra, back[0], action, okMessage);
        return "redirect:" + back[0];
    }

    private String go(RedirectAttributes ra, String back, Runnable action, String okMessage) {
        try { action.run(); ra.addFlashAttribute("ok", okMessage); }
        catch (ApiException e) { ra.addFlashAttribute("error", e.getMessage()); }
        catch (DataIntegrityViolationException e) { ra.addFlashAttribute("error", rootMessage(e)); }
        catch (Exception e) { ra.addFlashAttribute("error", rootMessage(e)); }
        return "redirect:" + back;
    }

    // ---- inbound
    @PostMapping("/receiving/{id}/arrive") public String arrive(@PathVariable UUID id, RedirectAttributes ra) { return go(ra, "/receiving/" + id, () -> receiving.arrive(id), "Trailer marked arrived."); }
    @PostMapping("/receiving/{id}/close")  public String close(@PathVariable UUID id, RedirectAttributes ra)  { return go(ra, "/receiving/" + id, () -> receiving.close(id), "Manifest closed — receiving is complete."); }
    @PostMapping("/receiving/{id}/cancel") public String cancel(@PathVariable UUID id, RedirectAttributes ra) { return go(ra, "/receiving/" + id, () -> receiving.cancel(id), "Manifest cancelled — nothing was received."); }
    @PostMapping("/receiving/{id}/pallet")
    public String receivePallet(@PathVariable UUID id, @RequestParam Map<String, String> f, RedirectAttributes ra) {
        return go(ra, "/receiving/" + id, () -> {
            Map<String, Object> r = new HashMap<>(f); r.remove("_csrf");
            Map<String, Object> out = receiving.receive(id, r);
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) out.get("putaway");
            ra.addFlashAttribute("ok", "Received " + out.get("lpn") + " at " + out.get("location")
                + (p == null ? " (cross-dock — no putaway)." : " — putaway " + p.get("assignmentNumber") + " directs it to " + p.get("destination") + ", check " + p.get("checkDigits") + "."));
        }, "Received.");
    }
    @PostMapping("/sites/{siteId}/manifests")
    public String createManifest(@PathVariable UUID siteId, @RequestParam Map<String, String> f, RedirectAttributes ra) {
        return go(ra, "/sites/" + siteId + "/receiving", () -> {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (String row : f.getOrDefault("lines", "").split("\\r?\\n")) {
                String[] p = row.trim().split("[,\\s]+");
                if (p.length >= 2) lines.add(Map.of("sku", p[0], "expectedQty", p[1]));
            }
            Map<String, Object> m = new HashMap<>(f); m.put("lines", lines);
            receiving.createManifest(siteId, m);
        }, "Manifest created.");
    }
    @PostMapping("/putaway/tasks/{taskId}/complete")
    public String completePutaway(@PathVariable UUID taskId, @RequestParam Map<String, String> f, @RequestParam UUID assignmentId, RedirectAttributes ra) {
        return go(ra, "/assignments/" + assignmentId, () -> putaway.complete(taskId, str(f.get("checkDigits")), str(f.get("overrideLocation")), str(f.get("overrideReason"))), "Pallet put away.");
    }

    // ---- dispatch
    @PostMapping("/assignments/{id}/assign")
    public String assign(@PathVariable UUID id, @RequestParam(required = false) String userId, @RequestParam(required = false) Integer priority, @RequestParam String back, RedirectAttributes ra) {
        return go(ra, local(back, "/"), () -> dispatch.assign(id, blank(userId) ? null : UUID.fromString(userId), priority), blank(userId) ? "Unassigned." : "Dispatched.");
    }

    // ---- outbound
    @PostMapping("/sites/{siteId}/orders")
    public String createOrder(@PathVariable UUID siteId, @RequestParam Map<String, String> f, RedirectAttributes ra) {
        return go(ra, "/sites/" + siteId + "/orders", () -> {
            List<Map<String, Object>> lines = new ArrayList<>();
            for (String row : f.getOrDefault("lines", "").split("\\r?\\n")) {
                String[] p = row.trim().split("[,\\s]+");
                if (p.length >= 2) lines.add(Map.of("sku", p[0], "qty", p[1]));
            }
            Map<String, Object> o = new HashMap<>(f); o.put("lines", lines); o.put("rush", f.containsKey("rush"));
            var r = orders.bulk(siteId, List.of(o));
            if (!r.ok()) throw ApiException.badRequest(r.errors.get(0).message());
            if (r.skipped > 0) throw ApiException.conflict("Order number already exists.");
        }, "Order created.");
    }
    @PostMapping("/sites/{siteId}/waves")
    public String plan(@PathVariable UUID siteId, @RequestParam Map<String, String> f, @RequestParam(name = "pick", required = false) List<String> picked, RedirectAttributes ra) {
        return go(ra, "/sites/" + siteId + "/waves", () -> {
            Map<String, Object> req = new HashMap<>(f);
            // Checked rows and typed numbers both name specific orders.
            List<String> numbers = new ArrayList<>(strings(f.get("orderNumbers")));
            if (picked != null) numbers.addAll(picked);
            if (!numbers.isEmpty()) req.put("orderNumbers", numbers); else req.remove("orderNumbers");
            Map<String, Object> w = waves.plan(siteId, req);
            ra.addFlashAttribute("ok", "Planned " + w.get("wave_number") + " with " + w.get("zone_orders") + " zone orders.");
        }, "Planned.");
    }
    @PostMapping("/waves/{id}/release")
    public String release(@PathVariable UUID id, @RequestParam(required = false) String equipment, @RequestParam(required = false) String build, RedirectAttributes ra) {
        return go(ra, "/waves/" + id, () -> {
            boolean manual = "MANUAL".equalsIgnoreCase(build);
            @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) waves.release(id, blank(equipment) ? null : equipment, manual).get("release");
            ra.addFlashAttribute("ok", "Released: " + (manual ? r.get("leftToBuild") + " zone order(s) waiting on the board to be built, " : r.get("assignmentsCut") + " assignment(s), ")
                + r.get("bypasses") + " shelf-life bypass(es), shortfall " + r.get("shortfall") + ".");
        }, "Released.");
    }

    // ---- work built on the floor
    @PostMapping("/sites/{siteId}/selection/build")
    public String build(@PathVariable UUID siteId, @RequestParam(name = "zo", required = false) List<UUID> zoneOrders, @RequestParam(required = false) String equipment,
                        @RequestParam(required = false) String userId, @RequestParam(required = false) String back, RedirectAttributes ra) {
        String[] to = { local(back, "/sites/" + siteId + "/board") };
        return go(ra, to, () -> {
            Map<String, Object> b = waves.build(siteId, zoneOrders, blank(equipment) ? null : equipment);
            if (!blank(userId)) dispatch.assign((UUID) b.get("assignmentId"), UUID.fromString(userId), null);
            to[0] = "/assignments/" + b.get("assignmentId");
            ra.addFlashAttribute("ok", "Built " + b.get("assignmentNumber") + " from " + b.get("zoneOrders") + " zone order(s)" + (b.get("equipment") != null ? " on " + b.get("equipment") : " — no unit yet") + ".");
        }, "Built.");
    }
    @PostMapping("/assignments/{id}/equipment")
    public String setEquipment(@PathVariable UUID id, @RequestParam String equipment, RedirectAttributes ra) {
        return go(ra, "/assignments/" + id, () -> selection.setEquipment(id, equipment), "Picking to " + equipment.toUpperCase() + ".");
    }
    @PostMapping("/sites/{siteId}/work/next")
    public String nextWork(@PathVariable UUID siteId, @RequestParam(required = false) String type, @RequestParam(required = false) String back, RedirectAttributes ra) {
        String[] to = { local(back, "/sites/" + siteId + "/board") };
        return go(ra, to, () -> {
            Map<String, Object> w = work.next(siteId, blank(type) ? null : type);
            to[0] = "/assignments/" + w.get("assignmentId");
            String how = switch (String.valueOf(w.get("how"))) { case "RESUMED" -> "You still have " + w.get("assignmentNumber") + " — finish it first."; case "BUILT" -> "Built " + w.get("assignmentNumber") + " for you from " + w.get("zoneOrders") + " released zone order(s)."; default -> w.get("assignmentNumber") + " is yours."; };
            ra.addFlashAttribute("ok", how + (w.get("equipment") != null ? " Picking to " + w.get("equipment") + "." : ""));
        }, "Assigned.");
    }
    @PostMapping("/selection/tasks/{taskId}/pick")
    public String pick(@PathVariable UUID taskId, @RequestParam Map<String, String> f, @RequestParam UUID assignmentId, RedirectAttributes ra) {
        return go(ra, "/assignments/" + assignmentId, () -> {
            Map<String, Object> r = selection.pick(taskId, str(f.get("checkDigits")), str(f.get("putCheckDigits")), number(f.get("qty")));
            ra.addFlashAttribute("ok", "Picked " + r.get("picked") + (Boolean.TRUE.equals(r.get("shorted")) ? " (short — remainder released)." : "."));
        }, "Picked.");
    }
    @PostMapping("/assignments/{id}/drop")
    public String drop(@PathVariable UUID id, @RequestParam String dropLocation, RedirectAttributes ra) {
        return go(ra, "/assignments/" + id, () -> selection.drop(id, dropLocation), "Dropped at " + dropLocation.toUpperCase() + ".");
    }
    @PostMapping("/sites/{siteId}/shipments")
    public String createShipment(@PathVariable UUID siteId, @RequestParam Map<String, String> f, @RequestParam(name = "orderNumbers", required = false) List<String> orderNumbers, RedirectAttributes ra) {
        return go(ra, "/sites/" + siteId + "/shipments", () -> {
            Map<String, Object> s = new HashMap<>(f); s.put("orderNumbers", orderNumbers == null ? List.of() : orderNumbers);
            Map<String, Object> out = shipping.create(siteId, s);
            ra.addFlashAttribute("ok", "Shipment " + out.get("shipment_number") + " created with packing list.");
        }, "Created.");
    }
    @PostMapping("/shipments/{id}/ship") public String ship(@PathVariable UUID id, RedirectAttributes ra) { return go(ra, "/shipments/" + id, () -> shipping.ship(id), "Shipped."); }

    // ---- replen
    @PostMapping("/replen/tasks/{taskId}/complete")
    public String replenComplete(@PathVariable UUID taskId, @RequestParam String checkDigits, @RequestParam UUID assignmentId, RedirectAttributes ra) {
        return go(ra, "/assignments/" + assignmentId, () -> { Map<String, Object> r = replen.complete(taskId, checkDigits); ra.addFlashAttribute("ok", r.get("lpnAtFace") + " now at the face."); }, "Done.");
    }

    // ---- alerts, org, users
    @PostMapping("/alerts/{id}/ack") public String ack(@PathVariable UUID id, @RequestParam String back, RedirectAttributes ra) { return go(ra, local(back, "/"), () -> alerts.acknowledge(id), "Acknowledged."); }
    @PostMapping("/notifications/read-all") public String readAll(RedirectAttributes ra) { return go(ra, "/notifications", alerts::markAllRead, "All read."); }
    @PostMapping("/org/{id}/policy")
    public String setPolicy(@PathVariable UUID id, @RequestParam Map<String, String> f, RedirectAttributes ra) {
        return go(ra, "/org/" + id + "/policy", () -> { Map<String, Object> p = new HashMap<>(); f.forEach((k, v) -> p.put(k, v.isBlank() ? null : v)); policy.set(id, p); }, "Policy saved.");
    }
    @PostMapping("/users")
    public String createUser(@RequestParam Map<String, String> f, @RequestParam(name = "workflows", required = false) List<String> workflows, RedirectAttributes ra) {
        return go(ra, "/operators", () -> {
            UUID id = users.upsert(TenantContext.corp(), str(f.get("email")), str(f.get("displayName")), str(f.get("password")), workflows == null ? List.of() : workflows);
            if (!blank(f.get("grantNode")) && !blank(f.get("grantRole"))) users.grant(id, UUID.fromString(f.get("grantNode")), f.get("grantRole"));
        }, "User saved.");
    }
    @PostMapping("/users/grant")
    public String grant(@RequestParam String email, @RequestParam UUID orgNodeId, @RequestParam String role, RedirectAttributes ra) {
        return go(ra, "/operators", () -> users.grant(users.idByEmail(email), orgNodeId, role), "Granted.");
    }
    @PostMapping("/operators/{id}/allowances")
    public String allowances(@PathVariable UUID id, @RequestParam(name = "workflows", required = false) List<String> workflows, RedirectAttributes ra) {
        List<String> w = workflows == null ? List.of() : workflows;
        return go(ra, "/operators/" + id, () -> users.setWorkflows(id, w), w.isEmpty() ? "Allowances cleared — this operator can't be dispatched." : "Trained for " + String.join(", ", w) + ".");
    }

    // ---- equipment custody: who has it, out and in
    @PostMapping("/equipment/{id}/checkout")
    public String checkout(@PathVariable UUID id, @RequestParam(required = false) String userId, @RequestParam String back, RedirectAttributes ra) {
        return go(ra, local(back, "/"), () -> equipment.checkout(id, blank(userId) ? null : UUID.fromString(userId)), "Checked out.");
    }
    @PostMapping("/equipment/{id}/checkin")
    public String checkin(@PathVariable UUID id, @RequestParam String back, RedirectAttributes ra) { return go(ra, local(back, "/"), () -> equipment.checkin(id), "Checked in."); }

    // ---- platform: a sysadmin picks the tenant to work in for this browser session
    @PostMapping("/platform/enter")
    public String enter(@RequestParam UUID corporationId, HttpServletRequest req, RedirectAttributes ra) {
        if (!TenantContext.sysadmin()) { ra.addFlashAttribute("error", "Only the platform sysadmin switches tenants."); return "redirect:/"; }
        if (org.corporations().stream().noneMatch(c -> corporationId.equals(c.get("org_node_id")))) { ra.addFlashAttribute("error", "No such tenant."); return "redirect:/"; }
        req.getSession().setAttribute(TenantFilter.SESSION_CORP, corporationId);
        req.getSession().removeAttribute(UiAdvice.SESSION_SITE);
        return "redirect:/";
    }
    @PostMapping("/platform/leave")
    public String leave(HttpServletRequest req) {
        req.getSession().removeAttribute(TenantFilter.SESSION_CORP); req.getSession().removeAttribute(UiAdvice.SESSION_SITE);
        return "redirect:/";
    }
}
