package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.UiService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orders master screen. Orders come from customers for specific items;
 * a wave is a collection of orders; assignments are built from released
 * orders per equipment. This screen filters by date and customer and is
 * where unreleased orders get rolled into a wave.
 */
@Controller
public class OrderViewController {

    private final JdbcTemplate jdbc;
    private final CapabilityService caps;
    private final UiService ui;

    public OrderViewController(JdbcTemplate jdbc, CapabilityService caps, UiService ui) {
        this.jdbc = jdbc; this.caps = caps; this.ui = ui;
    }

    @GetMapping("/orders")
    public String orders(@RequestParam(required = false) UUID siteId,
                         @RequestParam(required = false) String customer,
                         @RequestParam(required = false) String from,
                         @RequestParam(required = false) String to,
                         @RequestParam(required = false) String status, Model model) {
        caps.require(TenantContext.user(), null, Capabilities.DASHBOARD_VIEW);
        List<Map<String, Object>> sites = ui.sites();
        if (siteId == null && !sites.isEmpty()) siteId = (UUID) sites.get(0).get("org_node_id");
        model.addAttribute("siteId", siteId);
        model.addAttribute("userEmail", org.springframework.security.core.context
                .SecurityContextHolder.getContext().getAuthentication().getName());
        model.addAttribute("sysadmin", caps.isSysadmin(TenantContext.user()));
        model.addAttribute("customer", customer == null ? "" : customer.trim());
        model.addAttribute("from", from == null ? "" : from);
        model.addAttribute("to", to == null ? "" : to);
        model.addAttribute("status", status == null ? "" : status);

        StringBuilder where = new StringBuilder("o.site_id = ?");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(siteId);
        if (customer != null && !customer.isBlank()) {
            where.append(" AND (c.name ILIKE '%' || ? || '%' OR c.code ILIKE ? || '%')");
            args.add(customer.trim()); args.add(customer.trim());
        }
        if (from != null && !from.isBlank()) { where.append(" AND o.created_at >= ?::date"); args.add(from); }
        if (to != null && !to.isBlank())     { where.append(" AND o.created_at < ?::date + 1"); args.add(to); }
        if (status != null && !status.isBlank()) { where.append(" AND o.status::text = ?"); args.add(status); }

        model.addAttribute("orders", jdbc.queryForList("""
            SELECT o.order_id, o.order_number, o.status::text AS status, o.created_at,
                   c.customer_id, c.code AS customer_code, c.name AS customer_name,
                   c.route_code, count(col.order_line_id) AS lines,
                   COALESCE(sum(col.ordered_qty), 0) AS ordered_qty,
                   COALESCE(sum(col.picked_qty), 0) AS picked_qty,
                   w.wave_id, w.wave_number,
                   bool_or(a.assignment_id IS NOT NULL) AS on_assignment
            FROM customer_order o
            JOIN customer c ON c.customer_id = o.customer_id
            LEFT JOIN customer_order_line col ON col.order_id = o.order_id
            LEFT JOIN wave_order wo ON wo.order_id = o.order_id
            LEFT JOIN wave w ON w.wave_id = wo.wave_id
            LEFT JOIN assignment_container ac ON ac.order_id = o.order_id
            LEFT JOIN assignment a ON a.assignment_id = ac.assignment_id AND a.status <> 'CANCELLED'
            WHERE """ + " " + where + " " + """
            GROUP BY o.order_id, c.customer_id, w.wave_id
            ORDER BY o.created_at DESC LIMIT 100
            """, args.toArray()));
        model.addAttribute("equipment", jdbc.queryForList("""
            SELECT code, container_positions FROM equipment
            WHERE site_id = ? AND active ORDER BY code
            """, siteId));
        model.addAttribute("operators", jdbc.queryForList("""
            SELECT email::text AS email, display_name FROM app_user
            WHERE sysadmin = false AND active ORDER BY display_name
            """));
        return "orders";
    }

    /** Zone-order display status, shared shape across screens. */
    private static final String ZO_STATUS = """
        CASE WHEN zo.assignment_id IS NOT NULL THEN
                 CASE WHEN a.status = 'COMPLETE' THEN 'COMPLETE'
                      WHEN a.status = 'CANCELLED' THEN 'CANCELLED'
                      WHEN a.assigned_to IS NOT NULL THEN 'ASSIGNED'
                      ELSE 'BUILT' END
             WHEN w.status = 'RELEASED' THEN 'RELEASED'
             WHEN zo.wave_id IS NOT NULL THEN 'WAVED'
             ELSE 'UNRELEASED' END""";

    @GetMapping("/orders/{id}")
    public String order(@PathVariable UUID id, @RequestParam(required = false) UUID siteId, Model model) {
        caps.require(TenantContext.user(), null, Capabilities.DASHBOARD_VIEW);
        List<Map<String, Object>> sites = ui.sites();
        if (siteId == null && !sites.isEmpty()) siteId = (UUID) sites.get(0).get("org_node_id");
        model.addAttribute("siteId", siteId);
        model.addAttribute("userEmail", org.springframework.security.core.context
                .SecurityContextHolder.getContext().getAuthentication().getName());
        model.addAttribute("sysadmin", caps.isSysadmin(TenantContext.user()));
        model.addAttribute("o", jdbc.queryForMap("""
            SELECT o.order_id, o.order_number, o.status::text AS status, o.created_at,
                   o.requested_ship_date, l.code AS drop_location,
                   c.customer_id, c.name AS customer_name, c.code AS customer_code,
                   c.address_line1, c.address_line2, c.city, c.state_province, c.postal_code,
                   c.contact_phone, c.route_code, c.stop_sequence
            FROM customer_order o
            JOIN customer c ON c.customer_id = o.customer_id
            LEFT JOIN location l ON l.location_id = o.drop_location_id
            WHERE o.order_id = ?
            """, id));
        model.addAttribute("lines", jdbc.queryForList("""
            SELECT col.line_number, i.item_id, i.sku::text AS sku, i.description,
                   i.temp_zone::text AS temp_zone, tag_of(i.temp_zone::text) AS tag,
                   css_of(i.temp_zone::text) AS css, col.ordered_qty AS qty_ordered,
                   col.allocated_qty AS qty_allocated
            FROM customer_order_line col
            JOIN item i ON i.item_id = col.item_id
            WHERE col.order_id = ?
            ORDER BY col.line_number
            """, id));
        model.addAttribute("zoneOrders", jdbc.queryForList("""
            SELECT zo.zone_order_id, zo.temp_zone::text AS temp_zone,
                   tag_of(zo.temp_zone::text) AS tag, css_of(zo.temp_zone::text) AS css,
                   w.wave_id, w.wave_number, a.assignment_id, a.assignment_number, a.priority,
                   a.assigned_to AS assigned_user_id, u.display_name AS operator,
            """ + " " + ZO_STATUS + " AS display_status " + """
            FROM zone_order zo
            LEFT JOIN wave w ON w.wave_id = zo.wave_id
            LEFT JOIN assignment a ON a.assignment_id = zo.assignment_id
            LEFT JOIN app_user u ON u.user_id = a.assigned_to
            WHERE zo.order_id = ?
            ORDER BY zo.temp_zone
            """, id));
        return "order";
    }

    @GetMapping("/customers/{id}")
    public String customer(@PathVariable UUID id, @RequestParam(required = false) UUID siteId, Model model) {
        caps.require(TenantContext.user(), null, Capabilities.DASHBOARD_VIEW);
        List<Map<String, Object>> sites = ui.sites();
        if (siteId == null && !sites.isEmpty()) siteId = (UUID) sites.get(0).get("org_node_id");
        model.addAttribute("siteId", siteId);
        model.addAttribute("userEmail", org.springframework.security.core.context
                .SecurityContextHolder.getContext().getAuthentication().getName());
        model.addAttribute("sysadmin", caps.isSysadmin(TenantContext.user()));
        model.addAttribute("c", jdbc.queryForMap("""
            SELECT customer_id, code, name, address_line1, address_line2, city, state_province,
                   postal_code, country, contact_email, contact_phone, route_code, stop_sequence,
                   delivery_window_start, delivery_window_end, min_shelf_life_days, active, created_at
            FROM customer WHERE customer_id = ?
            """, id));
        model.addAttribute("history", jdbc.queryForList("""
            SELECT o.order_id, o.order_number, o.status::text AS status, o.created_at,
                   count(col.order_line_id) AS lines, COALESCE(sum(col.ordered_qty), 0) AS qty
            FROM customer_order o
            LEFT JOIN customer_order_line col ON col.order_id = o.order_id
            WHERE o.customer_id = ?
            GROUP BY o.order_id ORDER BY o.created_at DESC LIMIT 50
            """, id));
        return "customer";
    }
}
