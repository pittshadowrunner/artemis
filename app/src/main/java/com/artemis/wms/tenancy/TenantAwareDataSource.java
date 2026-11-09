package com.artemis.wms.tenancy;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Binds the request's tenant to every checked-out connection so Postgres Row
 * Level Security backstops the application-level filter. Policies are FORCED,
 * so this holds even though the app role owns the tables. Set on every
 * checkout (not just RESET when empty) so a pooled connection never carries a
 * previous request's tenant.
 */
public class TenantAwareDataSource extends DelegatingDataSource {
    public TenantAwareDataSource(DataSource target) { super(target); }

    @Override public Connection getConnection() throws SQLException { return bind(super.getConnection()); }
    @Override public Connection getConnection(String u, String p) throws SQLException { return bind(super.getConnection(u, p)); }

    private Connection bind(Connection c) throws SQLException {
        UUID corp = TenantContext.corp();
        boolean platform = TenantContext.sysadmin() && corp == null;   // inside a tenant, a sysadmin is scoped like anyone else
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT set_config('app.current_corp', ?, false), set_config('app.platform_admin', ?, false)")) {
            ps.setString(1, corp == null ? "" : corp.toString());
            ps.setString(2, platform ? "on" : "off");
            ps.execute();
        }
        return c;
    }
}
