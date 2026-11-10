package com.artemis.wms.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the authenticated principal to (user, corporation, sysadmin) and binds
 * it to the request thread. A sysadmin may act inside a tenant for one request
 * by sending X-Artemis-Corporation: <uuid>, or for a browser session by picking
 * the tenant on the platform page.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {
    public static final String CORP_HEADER = "X-Artemis-Corporation";
    public static final String SESSION_CORP = "artemis.corporation";
    private final JdbcTemplate jdbc;
    public TenantFilter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        try {
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                String email = auth.getPrincipal() instanceof OidcUser oidc ? oidc.getEmail() : auth.getName();
                List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT user_id, corporation_id, sysadmin, email::text AS email FROM app_user WHERE email = ?::citext AND active", email);
                if (!rows.isEmpty()) {
                    Map<String, Object> r = rows.get(0);
                    boolean sysadmin = Boolean.TRUE.equals(r.get("sysadmin"));
                    UUID corp = (UUID) r.get("corporation_id");
                    if (sysadmin) {
                        // A sysadmin belongs to no tenant. They enter one per request (the CLI's
                        // header) or for a browser session (the tenant picker on the platform page).
                        String header = req.getHeader(CORP_HEADER);
                        HttpSession session = req.getSession(false);
                        if (header != null && !header.isBlank()) corp = UUID.fromString(header.trim());
                        else if (session != null && session.getAttribute(SESSION_CORP) instanceof UUID chosen) corp = chosen;
                    }
                    TenantContext.set(new TenantContext.Principal((UUID) r.get("user_id"), corp, sysadmin, (String) r.get("email")));
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
