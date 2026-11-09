package com.artemis.wms.dataimport;

import com.artemis.wms.identity.AccessService;
import com.artemis.wms.identity.Capabilities;
import com.artemis.wms.identity.UserService;
import com.artemis.wms.inbound.ReceivingService;
import com.artemis.wms.inventory.InventoryService;
import com.artemis.wms.masterdata.CustomerService;
import com.artemis.wms.masterdata.EquipmentService;
import com.artemis.wms.masterdata.ItemService;
import com.artemis.wms.masterdata.LocationService;
import com.artemis.wms.org.OrgService;
import com.artemis.wms.org.PolicyService;
import com.artemis.wms.outbound.OrderService;
import com.artemis.wms.platform.ApiException;
import com.artemis.wms.platform.BulkResult;
import com.artemis.wms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.artemis.wms.platform.Values.*;

/**
 * One declarative document (YAML or JSON) → a whole tenant, or any part of
 * one. Sections run in dependency order and every section is idempotent
 * (upsert by code), so the same file can be applied again after an edit.
 * Deliberately NOT transactional as a whole: each section reports its own
 * created/updated/errors, and a bad pallet never rolls back a good rack.
 *
 * Sections: corporation, regions[sites[areas, policy]], policies, users,
 * customers, items, sites[locations, racks, equipment, containers,
 * inventory, manifests, orders].
 */
@Service
public class ImportService {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final OrgService org;
    private final PolicyService policy;
    private final UserService users;
    private final CustomerService customers;
    private final ItemService items;
    private final LocationService locations;
    private final EquipmentService equipment;
    private final InventoryService inventory;
    private final ReceivingService receiving;
    private final OrderService orders;

    public ImportService(JdbcTemplate jdbc, AccessService access, OrgService org, PolicyService policy, UserService users,
                         CustomerService customers, ItemService items, LocationService locations, EquipmentService equipment,
                         InventoryService inventory, ReceivingService receiving, OrderService orders) {
        this.jdbc = jdbc; this.access = access; this.org = org; this.policy = policy; this.users = users; this.customers = customers;
        this.items = items; this.locations = locations; this.equipment = equipment; this.inventory = inventory; this.receiving = receiving; this.orders = orders;
    }

    public Map<String, Object> apply(Map<String, Object> doc) {
        Map<String, Object> report = new LinkedHashMap<>();

        // 1. corporation — sysadmin creates or selects; everyone else imports into their own tenant
        Map<String, Object> corpSpec = map(doc.get("corporation"));
        UUID corp;
        if (!corpSpec.isEmpty()) {
            if (TenantContext.sysadmin()) {
                corp = org.createCorporation(str(corpSpec.get("code")), str(corpSpec.get("name")), corpSpec);
                TenantContext.enterTenant(corp);
            } else {
                corp = access.requireTenant();
                String code = jdbc.queryForObject("SELECT code FROM org_node WHERE org_node_id = ?", String.class, corp);
                if (!code.equalsIgnoreCase(str(corpSpec.get("code"))))
                    throw ApiException.forbidden("Document is for corporation " + corpSpec.get("code") + "; you are in " + code + ".");
            }
            report.put("corporation", Map.of("orgNodeId", corp, "code", upper(corpSpec.get("code"))));
        } else {
            corp = access.requireTenant();
        }
        access.require(corp, Capabilities.DATA_IMPORT);

        // 2. hierarchy
        BulkResult orgResult = new BulkResult();
        for (Map<String, Object> region : maps(doc.get("regions"))) {
            UUID regionId = node(orgResult, "DISTRICT_REGION", corp, region);
            if (regionId == null) continue;
            for (Map<String, Object> site : maps(region.get("sites"))) {
                UUID siteId = node(orgResult, "SITE_LOCATION", regionId, site);
                if (siteId == null) continue;
                for (Object a : (List<?>) site.getOrDefault("areas", List.of())) {
                    Map<String, Object> area = a instanceof Map ? map(a) : Map.of("code", a.toString(), "name", a.toString());
                    UUID areaId = node(orgResult, "AREA", siteId, area);
                    if (areaId != null && !map(area.get("policy")).isEmpty()) policy.set(areaId, map(area.get("policy")));
                }
                if (!map(site.get("policy")).isEmpty()) policy.set(siteId, map(site.get("policy")));
            }
        }
        if (doc.containsKey("regions")) report.put("hierarchy", orgResult);

        // 3. standalone policies: [{node: PIT1/FREEZER, rotation: FEFO, ...}]
        List<Map<String, Object>> policies = maps(doc.get("policies"));
        if (!policies.isEmpty()) {
            BulkResult r = new BulkResult(); int row = 0;
            for (Map<String, Object> p : policies) {
                row++;
                try { policy.set(org.resolve(str(p.get("node"))), p); r.updated(); }
                catch (Exception e) { r.error(row, str(p.get("node")), rootMessage(e)); }
            }
            report.put("policies", r);
        }

        // 4. users + grants
        List<Map<String, Object>> userSpecs = maps(doc.get("users"));
        if (!userSpecs.isEmpty()) {
            BulkResult r = new BulkResult(); int row = 0;
            for (Map<String, Object> u : userSpecs) {
                row++;
                String email = str(u.get("email"));
                try {
                    UUID userId = users.upsert(corp, email, str(u.get("displayName")), str(u.get("password")), strings(u.get("workflows")));
                    for (Map<String, Object> g : maps(u.get("grants"))) {
                        String node = str(g.get("node"));
                        UUID nodeId = node == null || isCorpCode(corp, node) ? corp : org.resolve(node);
                        users.grant(userId, nodeId, str(g.get("role")));
                    }
                    r.created();
                } catch (Exception e) { r.error(row, email, rootMessage(e)); }
            }
            report.put("users", r);
        }

        // 5. tenant-wide master data
        if (doc.containsKey("customers")) report.put("customers", customers.bulk(maps(doc.get("customers"))));
        if (doc.containsKey("items"))     report.put("items", items.bulk(maps(doc.get("items"))));

        // 6. site-scoped data
        for (Map<String, Object> s : maps(doc.get("sites"))) {
            String siteCode = str(s.get("site"));
            Map<String, Object> siteReport = new LinkedHashMap<>();
            try {
                UUID siteId = org.resolve(siteCode);
                if (s.containsKey("locations")) siteReport.put("locations", locations.bulk(siteId, maps(s.get("locations"))));
                List<Map<String, Object>> racks = maps(s.get("racks"));
                if (!racks.isEmpty()) {
                    BulkResult r = new BulkResult();
                    for (Map<String, Object> rack : racks) merge(r, locations.generate(siteId, rack));
                    siteReport.put("racks", r);
                }
                if (s.containsKey("equipment"))  siteReport.put("equipment", equipment.bulkEquipment(siteId, maps(s.get("equipment"))));
                if (s.containsKey("containers")) siteReport.put("containers", equipment.bulkContainers(siteId, maps(s.get("containers"))));
                if (s.containsKey("inventory"))  siteReport.put("inventory", inventory.openingBalances(siteId, maps(s.get("inventory"))));
                List<Map<String, Object>> manifests = maps(s.get("manifests"));
                if (!manifests.isEmpty()) {
                    BulkResult r = new BulkResult(); int row = 0;
                    for (Map<String, Object> m : manifests) {
                        row++;
                        try { receiving.createManifest(siteId, m); r.created(); }
                        catch (Exception e) { r.error(row, str(m.get("manifestNumber")), rootMessage(e)); }
                    }
                    siteReport.put("manifests", r);
                }
                if (s.containsKey("orders")) siteReport.put("orders", orders.bulk(siteId, maps(s.get("orders"))));
            } catch (Exception e) { siteReport.put("error", rootMessage(e)); }
            report.put("site:" + siteCode, siteReport);
        }
        return report;
    }

    private UUID node(BulkResult r, String level, UUID parent, Map<String, Object> spec) {
        String code = str(spec.get("code"));
        try {
            String name = str(spec.get("name")) == null ? code : str(spec.get("name"));
            boolean existed = jdbc.queryForObject("SELECT count(*) FROM org_node WHERE parent_id = ? AND code = ?", Integer.class, parent, upper(code)) > 0;
            UUID id = org.upsertNode(level, parent, code, name, spec);
            if (existed) r.updated(); else r.created();
            return id;
        } catch (Exception e) { r.error(0, level + " " + code, rootMessage(e)); return null; }
    }

    private boolean isCorpCode(UUID corp, String node) {
        String code = jdbc.queryForObject("SELECT code FROM org_node WHERE org_node_id = ?", String.class, corp);
        return code.equalsIgnoreCase(node) || node.equalsIgnoreCase("CORPORATION");
    }

    private static void merge(BulkResult into, BulkResult from) {
        into.created += from.created; into.updated += from.updated; into.skipped += from.skipped; into.errors.addAll(from.errors);
    }
}
