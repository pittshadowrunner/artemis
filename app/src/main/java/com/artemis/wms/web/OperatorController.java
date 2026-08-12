package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.UiService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operators are people, not assets — they get their own tab. A profile
 * carries workflow allowances (what the operator is trained in) and the
 * operator's own labor metrics, and is linkable from anywhere a person
 * is mentioned.
 */
@Controller
public class OperatorController {

    public static final List<String> WORKFLOWS =
        List.of("RECEIVING", "PUTAWAY", "REPLENISHMENT", "SELECTION", "SHIPPING");

    private final JdbcTemplate jdbc;
    private final CapabilityService caps;
    private final UiService ui;

    public OperatorController(JdbcTemplate jdbc, CapabilityService caps, UiService ui) {
        this.jdbc = jdbc; this.caps = caps; this.ui = ui;
    }

    private void common(UUID siteId, Model model) {
        caps.require(TenantContext.user(), null, Capabilities.DASHBOARD_VIEW);
        List<Map<String, Object>> sites = ui.sites();
        if (siteId == null && !sites.isEmpty()) siteId = (UUID) sites.get(0).get("org_node_id");
        model.addAttribute("siteId", siteId);
        if (siteId != null) model.addAttribute("crumb", ui.breadcrumb(siteId));
        model.addAttribute("userEmail", org.springframework.security.core.context
                .SecurityContextHolder.getContext().getAuthentication().getName());
        model.addAttribute("sysadmin", caps.isSysadmin(TenantContext.user()));
    }

    @GetMapping("/operators")
    public String operators(@RequestParam(required = false) UUID siteId, Model model) {
        common(siteId, model);
        model.addAttribute("operators", jdbc.queryForList("""
            SELECT u.user_id, u.display_name, u.email::text AS email, u.active,
                   u.trained_workflows,
                   count(a.assignment_id) FILTER (WHERE a.status NOT IN ('COMPLETE','CANCELLED')) AS open_work,
                   COALESCE(sum(lp.cases), 0) AS cases_today
            FROM app_user u
            LEFT JOIN assignment a ON a.assigned_to = u.user_id
            LEFT JOIN v_labor_productivity lp ON lp.user_id = u.user_id
            WHERE u.sysadmin = false
            GROUP BY u.user_id
            ORDER BY u.display_name
            """));
        return "operators";
    }

    @GetMapping("/operators/{id}")
    public String operator(@PathVariable UUID id, @RequestParam(required = false) UUID siteId, Model model) {
        common(siteId, model);
        Map<String, Object> op = jdbc.queryForMap("""
            SELECT user_id, display_name, email::text AS email, active,
                   trained_workflows, last_login_at, created_at
            FROM app_user WHERE user_id = ?
            """, id);
        model.addAttribute("op", op);
        model.addAttribute("workflows", WORKFLOWS);
        model.addAttribute("metrics", jdbc.queryForList("""
            SELECT assignment_type, sum(tasks) AS tasks, sum(cases) AS cases
            FROM v_labor_productivity WHERE user_id = ?
            GROUP BY assignment_type ORDER BY assignment_type
            """, id));
        model.addAttribute("work", jdbc.queryForList("""
            SELECT a.assignment_id, a.assignment_number, a.assignment_type::text AS assignment_type,
                   a.priority, a.created_at, w.wave_id, w.wave_number,
                   CASE WHEN a.status = 'CANCELLED' THEN 'CANCELLED'
                        WHEN a.status = 'COMPLETE' THEN 'COMPLETE'
                        WHEN count(t.task_id) FILTER (WHERE t.status = 'COMPLETE') > 0 THEN 'IN PROGRESS'
                        WHEN a.reassigned_count > 0 THEN 'REASSIGNED'
                        ELSE 'ASSIGNED' END AS display_status,
                   count(t.task_id) AS tasks,
                   count(t.task_id) FILTER (WHERE t.status = 'COMPLETE') AS done
            FROM assignment a
            LEFT JOIN wave w ON w.wave_id = a.wave_id
            LEFT JOIN assignment_task t ON t.assignment_id = a.assignment_id
            WHERE a.assigned_to = ?
            GROUP BY a.assignment_id, w.wave_id, w.wave_number
            ORDER BY a.created_at DESC LIMIT 25
            """, id));
        return "operator";
    }

    /** Workflow allowances: what this operator is trained to do. */
    @PostMapping("/ui/operators/allowances")
    public String allowances(@RequestParam Map<String, String> f, RedirectAttributes flash) {
        caps.require(TenantContext.user(), null, Capabilities.USER_MANAGE);
        String to = "/operators/" + f.get("userId") + "?siteId=" + f.get("siteId");
        java.util.ArrayList<String> allowed = new java.util.ArrayList<>();
        for (String w : WORKFLOWS) if (f.containsKey("wf_" + w)) allowed.add(w);
        jdbc.update("UPDATE app_user SET trained_workflows = ? WHERE user_id = ?",
            allowed.toArray(new String[0]), UUID.fromString(f.get("userId")));
        flash.addFlashAttribute("flash", "Allowances updated: " + String.join(", ", allowed) + ".");
        return "redirect:" + to;
    }
}
