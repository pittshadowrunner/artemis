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
}
