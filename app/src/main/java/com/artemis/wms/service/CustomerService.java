package com.artemis.wms.service;

import com.artemis.wms.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class CustomerService {

    private final JdbcTemplate jdbc;

    public CustomerService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID create(Map<String, Object> c) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO customer (customer_id, corporation_id, owner_org_id, code, name,
                address_line1, city, state_province, postal_code, country, contact_email,
                route_code, stop_sequence, min_shelf_life_days, pallet_build_pref, preferred_carrier)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::citext, ?, ?, ?, ?, ?)
            """, id, TenantContext.corp(), asUuid(c.get("ownerOrgId")),
            c.get("code"), c.get("name"), c.get("addressLine1"), c.get("city"),
            c.get("stateProvince"), c.get("postalCode"), c.get("country"), c.get("contactEmail"),
            c.get("routeCode"), asInt(c.get("stopSequence")), asInt(c.get("minShelfLifeDays")),
            c.get("palletBuildPref"), c.get("preferredCarrier"));
        return id;
    }

    public static UUID asUuid(Object o) { return o == null ? null : UUID.fromString(o.toString()); }
    public static Integer asInt(Object o) { return o == null || o.toString().isBlank() ? null : Integer.valueOf(o.toString()); }
}
