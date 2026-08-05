package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Platform console — everything here is sysadmin-gated. This is the seam
 * for system-wide administration: tenants, identity providers, platform
 * operators. Tenant data stays summary-level (counts, not contents);
 * sysadmin is a platform role, not a superuser over tenant operations.
 */
@Controller
public class SysadminController {

    private final JdbcTemplate jdbc;
    private final CapabilityService caps;

    public SysadminController(JdbcTemplate jdbc, CapabilityService caps) {
        this.jdbc = jdbc; this.caps = caps;
    }

    private List<Map<String, Object>> tenantRows() {
        return jdbc.queryForList("""
            SELECT c.org_node_id, c.code, c.name, c.active, c.created_at,
                   (SELECT count(*) FROM org_node s WHERE s.corporation_id = c.corporation_id
                     AND s.level = 'SITE_LOCATION')                                          AS sites,
                   (SELECT count(DISTINCT g.user_id) FROM user_org_grant g
                     JOIN org_node o ON o.org_node_id = g.org_node_id
                     WHERE o.corporation_id = c.corporation_id)                              AS users,
                   (SELECT count(*) FROM identity_provider ip
                     WHERE ip.corporation_id = c.corporation_id AND ip.active)               AS idps
            FROM org_node c WHERE c.level = 'CORPORATION' ORDER BY c.code
            """);
    }

    private List<Map<String, Object>> idpRows() {
        return jdbc.queryForList("""
            SELECT ip.registration_id, ip.display_name, ip.protocol::text AS protocol,
                   ip.issuer_uri, ip.jit_provisioning, ip.active, c.code AS tenant
            FROM identity_provider ip
            JOIN org_node c ON c.org_node_id = ip.corporation_id
            ORDER BY c.code, ip.registration_id
            """);
    }

    private List<Map<String, Object>> operatorRows() {
        return jdbc.queryForList("""
            SELECT email::text AS email, display_name, last_login_at, active
            FROM app_user WHERE sysadmin ORDER BY email
            """);
    }

    /** Server-rendered console page. */
    @GetMapping("/admin")
    public String console(Model model) {
        caps.requireSysadmin(TenantContext.user());
        model.addAttribute("tenants", tenantRows());
        model.addAttribute("idps", idpRows());
        model.addAttribute("operators", operatorRows());
        model.addAttribute("userEmail",
            org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName());
        model.addAttribute("asOf", LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d · HH:mm")));
        return "admin";
    }

    /** Same data over REST, for scripts and the future SPA. */
    @GetMapping("/api/v1/admin/tenants")
    @ResponseBody
    public List<Map<String, Object>> tenants() {
        caps.requireSysadmin(TenantContext.user());
        return tenantRows();
    }

    @GetMapping("/api/v1/admin/identity-providers")
    @ResponseBody
    public List<Map<String, Object>> idps() {
        caps.requireSysadmin(TenantContext.user());
        return idpRows();
    }
}
