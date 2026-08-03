package com.artemis.wms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * Resolves the authenticated principal to (userId, corporationId) and
 * binds both to the request thread. Corp comes from the user's grants —
 * the org ancestry root of their first grant.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbc;

    public TenantFilter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        try {
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                String email = auth.getPrincipal() instanceof OidcUser oidc
                        ? oidc.getEmail() : auth.getName();
                List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT u.user_id, o.corporation_id
                    FROM app_user u
                    LEFT JOIN user_org_grant g ON g.user_id = u.user_id
                    LEFT JOIN org_node o ON o.org_node_id = g.org_node_id
                    WHERE u.email = ?::citext AND u.active
                    LIMIT 1
                    """, email);
                if (!rows.isEmpty()) {
                    Map<String, Object> r = rows.get(0);
                    TenantContext.set((UUID) r.get("corporation_id"), (UUID) r.get("user_id"));
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
