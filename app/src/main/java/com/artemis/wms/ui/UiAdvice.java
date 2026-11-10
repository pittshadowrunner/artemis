package com.artemis.wms.ui;

import com.artemis.wms.alerts.AlertService;
import com.artemis.wms.org.OrgService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.platform.Values;
import com.artemis.wms.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the layout needs on every page — who, which site, the bell, the
 * link and format helpers — and what a page does when something isn't there:
 * a readable page with the right status, never a stack trace.
 */
@ControllerAdvice(basePackages = "com.artemis.wms.ui")
public class UiAdvice {
    private static final Logger log = LoggerFactory.getLogger(UiAdvice.class);
    static final String SESSION_SITE = "artemis.site";

    private final OrgService org;
    private final AlertService alerts;
    private final JdbcTemplate jdbc;
    public UiAdvice(OrgService org, AlertService alerts, JdbcTemplate jdbc) { this.org = org; this.alerts = alerts; this.jdbc = jdbc; }

    @ModelAttribute("me") public TenantContext.Principal me() { return TenantContext.current(); }
    @ModelAttribute("go") public Links go() { return new Links(); }
    @ModelAttribute("fmt") public Fmt fmt() { return new Fmt(); }

    /** Display formatting the templates shouldn't have to think about. */
    public static final class Fmt {
        /** JDBC hands back java.sql.Timestamp; #temporals won't format it. */
        public String dt(Object o, String pattern) {
            if (o == null) return "";
            java.time.temporal.TemporalAccessor t = o instanceof java.sql.Timestamp ts ? ts.toLocalDateTime()
                : o instanceof java.sql.Date d ? d.toLocalDate()
                : o instanceof java.time.Instant i ? java.time.LocalDateTime.ofInstant(i, java.time.ZoneId.systemDefault())
                : (java.time.temporal.TemporalAccessor) o;
            return java.time.format.DateTimeFormatter.ofPattern(pattern).format(t);
        }
        /** 48.000 → 48, 12.500 → 12.5. Quantities are numeric(14,3) in the database. */
        public String qty(Object o) {
            if (o == null) return "";
            if (o instanceof BigDecimal b) return b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString();
            return String.valueOf(o);
        }
        /** A Postgres interval as a person would say it: 42m, 3h 05m, 2d 4h. */
        public String age(Object o) {
            if (o == null) return "";
            // The driver (runtime-only dependency) prints "0 years 0 mons 2 days 3 hours 5 mins 12.4 secs".
            java.util.regex.Matcher part = INTERVAL_PART.matcher(String.valueOf(o));
            double minutes = 0; boolean any = false;
            while (part.find()) {
                any = true;
                double n = Double.parseDouble(part.group(1));
                minutes += switch (part.group(2)) {
                    case "year" -> n * 525960; case "mon" -> n * 43830; case "day" -> n * 1440; case "hour" -> n * 60; case "min" -> n; default -> n / 60;
                };
            }
            if (!any) return String.valueOf(o);
            long m = Math.max(0, Math.round(minutes));
            if (m < 1) return "just now";
            if (m < 60) return m + "m";
            if (m < 1440) return (m / 60) + "h " + String.format("%02d", m % 60) + "m";
            return (m / 1440) + "d " + ((m % 1440) / 60) + "h";
        }
        private static final java.util.regex.Pattern INTERVAL_PART = java.util.regex.Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(year|mon|day|hour|min|sec)");
        public String words(Object o) { return o == null ? "" : String.valueOf(o).replace('_', ' ').toLowerCase(); }
        public String list(Object o) { return o == null ? "" : String.join(", ", Values.pgList(o)); }
    }

    @ModelAttribute("sites") public List<Map<String, Object>> sites() {
        if (TenantContext.current() == null || TenantContext.corp() == null) return List.of();
        List<Map<String, Object>> s = safe(org::sites);
        return s == null ? List.of() : s;
    }

    @ModelAttribute("unread") public int unread() { return TenantContext.user() == null ? 0 : alerts.unreadCount(); }

    /**
     * The site the user is working in. A page under /sites/{id} sets it; pages
     * that aren't site-shaped (an item, a pallet, search) keep the last one, so
     * the navigation never changes shape depending on what was clicked.
     */
    @SuppressWarnings("unchecked")
    @ModelAttribute("siteId") public UUID siteId(HttpServletRequest req) {
        List<Map<String, Object>> sites = sites();
        Map<String, String> vars = (Map<String, String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars != null && vars.get("siteId") != null) {
            try { UUID id = UUID.fromString(vars.get("siteId")); req.getSession().setAttribute(SESSION_SITE, id); return id; }
            catch (IllegalArgumentException ignored) { /* falls through to the remembered site */ }
        }
        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute(SESSION_SITE) instanceof UUID remembered
                && sites.stream().anyMatch(s -> remembered.equals(s.get("site_id")))) return remembered;
        return sites.isEmpty() ? null : (UUID) sites.get(0).get("site_id");
    }

    /** A page about one thing pins the site that thing lives at. */
    static void pin(HttpServletRequest req, Model m, Object siteId) {
        if (siteId instanceof UUID id) { req.getSession().setAttribute(SESSION_SITE, id); m.addAttribute("siteId", id); }
    }
    static UUID current(HttpServletRequest req, Model m) { return (UUID) m.getAttribute("siteId"); }

    /** Which top-bar entry is "here". */
    @ModelAttribute("section") public String section(HttpServletRequest req) {
        String p = req.getRequestURI();
        for (String[] rule : new String[][] {
                {"/board", "board"}, {"/racking", "locations"}, {"/slots", "locations"}, {"/pallets", "pallets"}, {"/lpn", "pallets"}, {"/lots", "pallets"},
                {"/receiving", "receiving"}, {"/orders", "orders"}, {"/waves", "waves"}, {"/assignments", "board"}, {"/shipments", "shipping"},
                {"/dashboard", "dashboard"}, {"/alerts", "board"}, {"/items", "items"}, {"/customers", "customers"}, {"/operators", "operators"},
                {"/users", "operators"}, {"/equipment", "equipment"}, {"/org", "org"}, {"/search", "search"}})
            if (p.contains(rule[0])) return rule[1];
        return "";
    }

    /** For a sysadmin working inside a tenant: which one, so the bar can say so and offer the way out. */
    @ModelAttribute("tenant") public String tenant() {
        if (!TenantContext.sysadmin() || TenantContext.corp() == null) return null;
        return safe(() -> jdbc.queryForObject("SELECT code FROM org_node WHERE org_node_id = ?", String.class, TenantContext.corp()));
    }

    // ------------------------------------------------------------ errors
    @ExceptionHandler(ApiException.class)
    public ModelAndView api(ApiException e, HttpServletRequest req) {
        // A sysadmin who hasn't picked a tenant belongs on the tenant list, not on an error page.
        if (TenantContext.sysadmin() && TenantContext.corp() == null) return new ModelAndView("redirect:/");
        return page(e.status(), e.getMessage(), req);
    }
    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ModelAndView missing(EmptyResultDataAccessException e, HttpServletRequest req) { return page(HttpStatus.NOT_FOUND, "That record doesn't exist (or isn't in this tenant).", req); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ModelAndView badKey(MethodArgumentTypeMismatchException e, HttpServletRequest req) { return page(HttpStatus.NOT_FOUND, "Nothing lives at that address.", req); }
    @ExceptionHandler(Exception.class)
    public ModelAndView other(Exception e, HttpServletRequest req) throws Exception {
        if (e instanceof org.springframework.web.ErrorResponse || e instanceof org.springframework.security.access.AccessDeniedException) throw e;   // framework's own 4xx
        log.error("Page {} failed", req.getRequestURI(), e);
        return page(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on this page: " + Values.rootMessage(e), req);
    }

    private ModelAndView page(HttpStatus status, String message, HttpServletRequest req) {
        ModelAndView mv = new ModelAndView("error");
        mv.setStatus(status);
        mv.addObject("status", status.value()); mv.addObject("error", status.getReasonPhrase());
        mv.addObject("message", message); mv.addObject("path", req.getRequestURI());
        // @ModelAttribute methods don't run for an exception view; give the layout what it needs.
        mv.addObject("me", me()); mv.addObject("go", go()); mv.addObject("fmt", fmt()); mv.addObject("sites", safeOr(this::sites, List.of()));
        mv.addObject("unread", safeOr(this::unread, 0)); mv.addObject("siteId", safeOr(() -> siteId(req), null));
        mv.addObject("section", section(req)); mv.addObject("tenant", safeOr(this::tenant, null));
        return mv;
    }

    private static <T> T safe(java.util.function.Supplier<T> s) { try { return s.get(); } catch (Exception e) { return null; } }
    private static <T> T safeOr(java.util.function.Supplier<T> s, T fallback) { try { return s.get(); } catch (Exception e) { return fallback; } }
}
