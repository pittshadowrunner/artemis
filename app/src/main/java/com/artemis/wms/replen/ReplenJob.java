package com.artemis.wms.replen;

import com.artemis.wms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The replenishment feed. Picks, receipts and putaways change what a face
 * holds; this scan notices and cuts the work, so the board shows replenishment
 * as it becomes necessary and nobody has to go looking for it.
 *
 * It runs outside any request, so it finds the sites that need attention as
 * platform tier and then enters each tenant in turn — every row it writes goes
 * through the same Row Level Security as a user's would.
 */
@Component
public class ReplenJob {
    private static final Logger log = LoggerFactory.getLogger(ReplenJob.class);
    private final JdbcTemplate jdbc;
    private final ReplenService replen;
    private final boolean enabled;

    public ReplenJob(JdbcTemplate jdbc, ReplenService replen, @Value("${artemis.replen.auto:true}") boolean enabled) {
        this.jdbc = jdbc; this.replen = replen; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${artemis.replen.scan-fixed-delay-ms:60000}", initialDelayString = "${artemis.replen.scan-initial-delay-ms:30000}")
    public void scan() {
        if (!enabled) return;
        try {
            TenantContext.set(new TenantContext.Principal(null, null, true, "system:replen"));
            List<Map<String, Object>> sites = jdbc.queryForList(
                "SELECT DISTINCT corporation_id, site_id FROM v_replen_pressure WHERE on_hand + in_flight <= replen_trigger_qty");
            for (Map<String, Object> s : sites) {
                UUID corp = (UUID) s.get("corporation_id"), site = (UUID) s.get("site_id");
                try {
                    TenantContext.set(new TenantContext.Principal(null, corp, true, "system:replen"));
                    Map<String, Object> r = replen.cut(site, corp);
                    if ((int) r.get("created") > 0) log.info("Replenishment: cut {} task(s) at site {}", r.get("created"), site);
                } catch (Exception e) {
                    log.warn("Replenishment scan failed at site {}: {}", site, e.getMessage());
                }
            }
        } finally {
            TenantContext.clear();
        }
    }
}
