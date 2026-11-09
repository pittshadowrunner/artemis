package com.artemis.wms.ui;

import com.artemis.wms.alerts.AlertService;
import com.artemis.wms.identity.UserService;
import com.artemis.wms.inbound.PutawayService;
import com.artemis.wms.inbound.ReceivingService;
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

    public UiActionController(ReceivingService receiving, PutawayService putaway, DispatchService dispatch, OrderService orders, WaveService waves,
                              SelectionService selection, ShippingService shipping, ReplenService replen, AlertService alerts, PolicyService policy, UserService users) {
        this.receiving = receiving; this.putaway = putaway; this.dispatch = dispatch; this.orders = orders; this.waves = waves; this.selection = selection;
        this.shipping = shipping; this.replen = replen; this.alerts = alerts; this.policy = policy; this.users = users;
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
    @PostMapping("/receiving/{id}/close")  public String close(@PathVariable UUID id, RedirectAttributes ra)  { return go(ra, "/receiving/" + id, () -> receiving.close(id), "Manifest closed."); }
    @PostMapping("/receiving/{id}/pallet")
    public String receivePallet(@PathVariable UUID id, @RequestParam Map<String, String> f, RedirectAttributes ra) {
        return go(ra, "/receiving/" + id, () -> {
            Map<String, Object> out = receiving.receive(id, new HashMap<>(f));
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) out.get("putaway");
            ra.addFlashAttribute("ok", "Received " + out.get("lpn") + (p == null ? "." : " — putaway to " + p.get("destination") + ", check " + p.get("checkDigits") + "."));
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
        return go(ra, back, () -> dispatch.assign(id, blank(userId) ? null : UUID.fromString(userId), priority), blank(userId) ? "Unassigned." : "Dispatched.");
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
    public String plan(@PathVariable UUID siteId, @RequestParam Map<String, String> f, RedirectAttributes ra) {
        return go(ra, "/sites/" + siteId + "/waves", () -> {
            Map<String, Object> w = waves.plan(siteId, new HashMap<>(f));
            ra.addFlashAttribute("ok", "Planned " + w.get("wave_number") + " with " + w.get("zone_orders") + " zone orders.");
        }, "Planned.");
    }
    @PostMapping("/waves/{id}/release")
    public String release(@PathVariable UUID id, @RequestParam(required = false) String equipment, RedirectAttributes ra) {
        return go(ra, "/waves/" + id, () -> {
            @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) waves.release(id, blank(equipment) ? null : equipment).get("release");
            ra.addFlashAttribute("ok", "Released: " + r.get("assignmentsCut") + " assignment(s), " + r.get("bypasses") + " shelf-life bypass(es), shortfall " + r.get("shortfall") + ".");
        }, "Released.");
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
    @PostMapping("/sites/{siteId}/replen/run")
    public String replenRun(@PathVariable UUID siteId, RedirectAttributes ra) {
        return go(ra, "/sites/" + siteId + "/board", () -> { Map<String, Object> r = replen.run(siteId); ra.addFlashAttribute("ok", "Replenishment: " + r.get("created") + " task(s) cut, " + r.get("noSource") + " face(s) with no reserve stock."); }, "Run.");
    }
    @PostMapping("/replen/tasks/{taskId}/complete")
    public String replenComplete(@PathVariable UUID taskId, @RequestParam String checkDigits, @RequestParam UUID assignmentId, RedirectAttributes ra) {
        return go(ra, "/assignments/" + assignmentId, () -> { Map<String, Object> r = replen.complete(taskId, checkDigits); ra.addFlashAttribute("ok", r.get("lpnAtFace") + " now at the face."); }, "Done.");
    }

    // ---- alerts, org, users
    @PostMapping("/alerts/{id}/ack") public String ack(@PathVariable UUID id, @RequestParam String back, RedirectAttributes ra) { return go(ra, back, () -> alerts.acknowledge(id), "Acknowledged."); }
    @PostMapping("/notifications/read-all") public String readAll(RedirectAttributes ra) { return go(ra, "/notifications", alerts::markAllRead, "All read."); }
    @PostMapping("/org/{id}/policy")
    public String setPolicy(@PathVariable UUID id, @RequestParam Map<String, String> f, RedirectAttributes ra) {
        return go(ra, "/org/" + id + "/policy", () -> { Map<String, Object> p = new HashMap<>(); f.forEach((k, v) -> p.put(k, v.isBlank() ? null : v)); policy.set(id, p); }, "Policy saved.");
    }
    @PostMapping("/users")
    public String createUser(@RequestParam Map<String, String> f, @RequestParam(name = "workflows", required = false) List<String> workflows, RedirectAttributes ra) {
        return go(ra, "/users", () -> {
            UUID id = users.upsert(TenantContext.corp(), str(f.get("email")), str(f.get("displayName")), str(f.get("password")), workflows == null ? List.of() : workflows);
            if (!blank(f.get("grantNode")) && !blank(f.get("grantRole"))) users.grant(id, UUID.fromString(f.get("grantNode")), f.get("grantRole"));
        }, "User saved.");
    }
    @PostMapping("/users/grant")
    public String grant(@RequestParam String email, @RequestParam UUID orgNodeId, @RequestParam String role, RedirectAttributes ra) {
        return go(ra, "/users", () -> users.grant(users.idByEmail(email), orgNodeId, role), "Granted.");
    }
}
