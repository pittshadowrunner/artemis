package com.artemis.wms.security;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Sets `app.current_corp` on every checked-out connection so Postgres
 * Row Level Security backstops the application-level tenant filter.
 * Even a bug in the app layer can't leak cross-tenant data.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource target) { super(target); }

    @Override
    public Connection getConnection() throws SQLException {
        Connection c = super.getConnection();
        apply(c);
        return c;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection c = super.getConnection(username, password);
        apply(c);
        return c;
    }

    private void apply(Connection c) throws SQLException {
        UUID corp = TenantContext.corp();
        try (Statement st = c.createStatement()) {
            if (corp != null) {
                st.execute("SET app.current_corp = '" + corp + "'");
            } else {
                st.execute("RESET app.current_corp");
            }
        }
    }
}
