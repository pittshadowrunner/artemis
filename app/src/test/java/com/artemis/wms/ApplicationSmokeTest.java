package com.artemis.wms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the app against the CI Postgres (see .github/workflows/ci.yml),
 * which runs every migration as a non-superuser and proves the schema
 * still applies clean. Deeper flows are exercised by ops/smoke.sh.
 */
@SpringBootTest
@ActiveProfiles("local-auth")
class ApplicationSmokeTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void migrationsApplyAndTenantIsolationIsForced() {
        Integer forced = jdbc.queryForObject(
            "SELECT count(*) FROM pg_class WHERE relforcerowsecurity AND relname IN ('org_node','item','location','inventory','assignment')", Integer.class);
        assertEquals(5, forced, "tenant tables must FORCE row level security");
        Integer roles = jdbc.queryForObject("SELECT count(*) FROM role WHERE corporation_id IS NULL", Integer.class);
        assertEquals(4, roles);
        // no tenant bound on this thread → tenant tables are invisible
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM org_node", Integer.class));
    }
}
