package com.artemis.wms.masterdata;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.org.OrgService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.platform.BulkResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static com.artemis.wms.platform.Values.*;

@Service
public class CustomerService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final OrgService org;
    private final TransactionTemplate tx;
    public CustomerService(JdbcTemplate jdbc, AccessService access, OrgService org, TransactionTemplate tx) { this.jdbc = jdbc; this.access = access; this.org = org; this.tx = tx; }

    /** Upsert by code. ownerOrgId or owner (code path) says which site/region owns the account. */
    public BulkResult bulk(List<Map<String, Object>> customers) {
        UUID corp = access.requireTenant();
        access.require(null, Capabilities.CUSTOMER_MANAGE);
        BulkResult r = new BulkResult();
        int row = 0;
        for (Map<String, Object> c : customers) {
            row++;
            String code = upper(c.get("code"));
            try {
                if (code == null || str(c.get("name")) == null) throw ApiException.badRequest("code and name are required.");
                UUID owner = c.get("ownerOrgId") != null ? uuid(c.get("ownerOrgId")) : c.get("owner") != null ? org.resolve(str(c.get("owner"))) : corp;
                boolean created = tx.execute(s -> upsert(corp, owner, code, c));
                if (created) r.created(); else r.updated();
            } catch (ApiException e) { r.error(row, code, e.getMessage()); }
              catch (Exception e)    { r.error(row, code, rootMessage(e)); }
        }
        return r;
    }

    private boolean upsert(UUID corp, UUID owner, String code, Map<String, Object> c) {
        Object[] a = { str(c.get("name")), str(c.get("addressLine1")), str(c.get("addressLine2")), str(c.get("city")), str(c.get("stateProvince")), str(c.get("postalCode")), str(c.get("country")),
            str(c.get("contactEmail")), str(c.get("contactPhone")), str(c.get("routeCode")), integer(c.get("stopSequence")),
            str(c.get("deliveryWindowStart")), str(c.get("deliveryWindowEnd")), integer(c.get("minShelfLifeDays")),
            bool(c.get("requiresGs1Labels")), bool(c.get("requiresAsn")), upper(c.get("palletBuildPref")), str(c.get("preferredCarrier")) };
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM customer WHERE corporation_id = ? AND code = ?", Integer.class, corp, code);
        if (exists != null && exists > 0) {
            List<Object> u = new ArrayList<>(); u.add(owner); u.addAll(Arrays.asList(a)); u.add(corp); u.add(code);
            jdbc.update("""
                UPDATE customer SET owner_org_id = ?, name = ?, address_line1 = COALESCE(?, address_line1), address_line2 = COALESCE(?, address_line2), city = COALESCE(?, city), state_province = COALESCE(?, state_province), postal_code = COALESCE(?, postal_code), country = COALESCE(?, country),
                    contact_email = COALESCE(?::citext, contact_email), contact_phone = COALESCE(?, contact_phone), route_code = COALESCE(?, route_code), stop_sequence = COALESCE(?, stop_sequence),
                    delivery_window_start = COALESCE(?::time, delivery_window_start), delivery_window_end = COALESCE(?::time, delivery_window_end), min_shelf_life_days = COALESCE(?, min_shelf_life_days),
                    requires_gs1_labels = COALESCE(?, requires_gs1_labels), requires_asn = COALESCE(?, requires_asn), pallet_build_pref = COALESCE(?, pallet_build_pref), preferred_carrier = COALESCE(?, preferred_carrier),
                    active = true, updated_at = now()
                WHERE corporation_id = ? AND code = ?""", u.toArray());
            return false;
        }
        List<Object> i = new ArrayList<>(List.of(corp, owner, code)); i.addAll(Arrays.asList(a));
        jdbc.update("""
            INSERT INTO customer (corporation_id, owner_org_id, code, name, address_line1, address_line2, city, state_province, postal_code, country,
                contact_email, contact_phone, route_code, stop_sequence, delivery_window_start, delivery_window_end, min_shelf_life_days,
                requires_gs1_labels, requires_asn, pallet_build_pref, preferred_carrier)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::citext, ?, ?, ?, ?::time, ?::time, ?, COALESCE(?, false), COALESCE(?, false), ?, ?)""", i.toArray());
        return true;
    }

    public List<Map<String, Object>> list(String q) {
        return jdbc.queryForList("SELECT customer_id, code, name, city, state_province, route_code, stop_sequence, min_shelf_life_days, preferred_carrier, active FROM customer WHERE corporation_id = ? AND (?::text IS NULL OR code ILIKE '%' || ? || '%' OR name ILIKE '%' || ? || '%') ORDER BY code",
            access.requireTenant(), q, q, q);
    }

    public Map<String, Object> get(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT c.*, c.contact_email::text AS contact_email, o.code AS owner FROM customer c JOIN org_node o ON o.org_node_id = c.owner_org_id WHERE c.corporation_id = ? AND c.code = ?", access.requireTenant(), code.toUpperCase());
        if (rows.isEmpty()) throw ApiException.notFound("Customer " + code + " not found.");
        return rows.get(0);
    }
}
