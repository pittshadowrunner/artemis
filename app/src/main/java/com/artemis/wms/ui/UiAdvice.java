package com.artemis.wms.ui;

import com.artemis.wms.alerts.AlertService;
import com.artemis.wms.org.OrgService;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.HandlerMapping;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything the layout needs on every page: who, which sites, bell count, current site. */
@ControllerAdvice(basePackages = "com.artemis.wms.ui")
public class UiAdvice {
    private final OrgService org;
    private final AlertService alerts;
    public UiAdvice(OrgService org, AlertService alerts) { this.org = org; this.alerts = alerts; }

    @ModelAttribute("me") public TenantContext.Principal me() { return TenantContext.current(); }

    /** JDBC hands back java.sql.Timestamp; #temporals won't format it. */
    public static final class Fmt {
        public String dt(Object o, String pattern) {
            if (o == null) return "";
            java.time.temporal.TemporalAccessor t = o instanceof java.sql.Timestamp ts ? ts.toLocalDateTime()
                : o instanceof java.sql.Date d ? d.toLocalDate()
                : o instanceof java.time.Instant i ? java.time.LocalDateTime.ofInstant(i, java.time.ZoneId.systemDefault())
                : (java.time.temporal.TemporalAccessor) o;
            return java.time.format.DateTimeFormatter.ofPattern(pattern).format(t);
        }
    }
    @ModelAttribute("fmt") public Fmt fmt() { return new Fmt(); }

    @ModelAttribute("sites") public List<Map<String, Object>> sites() {
        return TenantContext.current() == null || (TenantContext.corp() == null && !TenantContext.sysadmin()) ? List.of() : safe(org::sites);
    }

    @ModelAttribute("unread") public int unread() { return TenantContext.user() == null ? 0 : alerts.unreadCount(); }

    @SuppressWarnings("unchecked")
    @ModelAttribute("siteId") public UUID siteId(HttpServletRequest req) {
        Map<String, String> vars = (Map<String, String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars != null && vars.get("siteId") != null) return UUID.fromString(vars.get("siteId"));
        List<Map<String, Object>> s = sites();
        return s.size() == 1 ? (UUID) s.get(0).get("site_id") : null;
    }

    private static <T> T safe(java.util.function.Supplier<T> s) { try { return s.get(); } catch (Exception e) { return null; } }
}
