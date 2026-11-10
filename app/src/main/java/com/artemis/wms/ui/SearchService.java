package com.artemis.wms.ui;

import com.artemis.wms.identity.AccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Universal search: one box for anything with a name. Looks across pallets,
 * locations, items, lots, orders, customers, waves, assignments, manifests,
 * shipments, operators and equipment inside the caller's tenant. Exact
 * matches come first; a query that names exactly one thing goes straight to it.
 */
@Service
public class SearchService {
    /** One search result: what kind of thing, its name, a line of context, and where it lives. */
    public record Hit(String kind, String label, String key, String detail, String url, boolean exact) {}

    private static final int PER_KIND = 12;
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final Links go = new Links();
    public SearchService(JdbcTemplate jdbc, AccessService access) { this.jdbc = jdbc; this.access = access; }

    public List<Hit> search(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return List.of();
        UUID corp = access.requireTenant();
        // One or two characters match from the start only; longer queries match anywhere.
        String like = (q.length() < 3 ? "" : "%") + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<Hit> hits = new ArrayList<>();

        add(hits, "Pallet", q, """
            SELECT v.lpn AS key, i.sku::text || ' · ' || trim_scale(v.qty) || ' · ' || COALESCE(l.code, 'in transit') || ' · ' || v.status::text AS detail, NULL::text AS extra
            FROM inventory v JOIN item i ON i.item_id = v.item_id LEFT JOIN location l ON l.location_id = v.location_id
            WHERE v.corporation_id = ? AND v.lpn ILIKE ? ORDER BY (upper(v.lpn) = upper(?)) DESC, v.created_at DESC LIMIT ?""", corp, like);
        add(hits, "Location", q, """
            SELECT l.code AS key, replace(l.loc_type::text, '_', ' ') || ' · ' || l.temp_zone::text || COALESCE(' · pick face ' || i.sku::text, '') AS detail, NULL::text AS extra
            FROM location l LEFT JOIN item i ON i.item_id = l.replen_item_id
            WHERE l.corporation_id = ? AND l.code ILIKE ? ORDER BY (upper(l.code) = upper(?)) DESC, l.pick_sequence NULLS LAST, l.code LIMIT ?""", corp, like);
        add(hits, "Item", q, """
            SELECT i.sku::text AS key, i.description || ' · ' || i.temp_zone::text AS detail, NULL::text AS extra
            FROM item i WHERE i.corporation_id = ? AND (i.sku::text ILIKE ? OR i.description ILIKE ?) ORDER BY (upper(i.sku::text) = upper(?)) DESC, i.sku LIMIT ?""", corp, like, like);
        add(hits, "Lot", q, """
            SELECT v.lot_number AS key, v.sku::text || ' · ' || trim_scale(sum(v.qty_on_hand)) || ' on hand' || COALESCE(' · expires ' || min(v.expiration_date), '') AS detail, v.sku::text AS extra
            FROM v_lot v WHERE v.corporation_id = ? AND v.lot_number ILIKE ? GROUP BY v.lot_number, v.sku ORDER BY (upper(v.lot_number) = upper(?)) DESC, v.lot_number LIMIT ?""", corp, like);
        add(hits, "Order", q, """
            SELECT o.order_number AS key, c.name || ' · ' || o.status::text || COALESCE(' · ship ' || o.requested_ship_date, '') AS detail, NULL::text AS extra
            FROM customer_order o JOIN customer c ON c.customer_id = o.customer_id
            WHERE o.corporation_id = ? AND o.order_number ILIKE ? ORDER BY (upper(o.order_number) = upper(?)) DESC, o.created_at DESC LIMIT ?""", corp, like);
        add(hits, "Customer", q, """
            SELECT c.code AS key, c.name || COALESCE(' · ' || c.city, '') || COALESCE(', ' || c.state_province, '') AS detail, NULL::text AS extra
            FROM customer c WHERE c.corporation_id = ? AND (c.code ILIKE ? OR c.name ILIKE ?) ORDER BY (upper(c.code) = upper(?)) DESC, c.code LIMIT ?""", corp, like, like);
        add(hits, "Wave", q, """
            SELECT w.wave_number AS key, w.wave_type::text || ' · ' || w.temp_zone::text || ' · ' || w.status::text AS detail, NULL::text AS extra
            FROM wave w WHERE w.corporation_id = ? AND w.wave_number ILIKE ? ORDER BY (upper(w.wave_number) = upper(?)) DESC, w.created_at DESC LIMIT ?""", corp, like);
        add(hits, "Assignment", q, """
            SELECT a.assignment_number AS key, a.assignment_type::text || ' · ' || a.status::text || ' · P' || a.priority || COALESCE(' · ' || u.display_name, '') AS detail, NULL::text AS extra
            FROM assignment a LEFT JOIN app_user u ON u.user_id = a.assigned_to
            WHERE a.corporation_id = ? AND a.assignment_number ILIKE ? ORDER BY (upper(a.assignment_number) = upper(?)) DESC, a.created_at DESC LIMIT ?""", corp, like);
        add(hits, "Manifest", q, """
            SELECT m.manifest_number AS key, COALESCE(m.carrier || ' · ', '') || COALESCE('trailer ' || m.trailer_number || ' · ', '') || m.status::text AS detail, NULL::text AS extra
            FROM receiving_manifest m WHERE m.corporation_id = ? AND (m.manifest_number ILIKE ? OR m.trailer_number ILIKE ?)
            ORDER BY (upper(m.manifest_number) = upper(?)) DESC, m.created_at DESC LIMIT ?""", corp, like, like);
        add(hits, "Shipment", q, """
            SELECT s.shipment_number AS key, c.name || ' · ' || s.status::text || COALESCE(' · ' || s.carrier, '') AS detail, NULL::text AS extra
            FROM shipment s JOIN customer c ON c.customer_id = s.customer_id
            WHERE s.corporation_id = ? AND (s.shipment_number ILIKE ? OR s.trailer_number ILIKE ?) ORDER BY (upper(s.shipment_number) = upper(?)) DESC, s.created_at DESC LIMIT ?""", corp, like, like);
        add(hits, "Operator", q, """
            SELECT u.display_name AS key, u.email::text || COALESCE(' · ' || array_to_string(u.trained_workflows, ', '), '') AS detail, u.user_id::text AS extra
            FROM app_user u WHERE u.corporation_id = ? AND (u.display_name ILIKE ? OR u.email::text ILIKE ?) ORDER BY (upper(u.display_name) = upper(?)) DESC, u.display_name LIMIT ?""", corp, like, like);
        add(hits, "Equipment", q, """
            SELECT e.code AS key, replace(e.equipment_type::text, '_', ' ') || COALESCE(' · ' || e.container_positions || ' positions', '')
                   || COALESCE(' · with ' || (SELECT u.display_name FROM equipment_checkout co JOIN app_user u ON u.user_id = co.user_id WHERE co.equipment_id = e.equipment_id AND co.checked_in_at IS NULL), '') AS detail, NULL::text AS extra
            FROM equipment e WHERE e.corporation_id = ? AND e.code ILIKE ? ORDER BY (upper(e.code) = upper(?)) DESC, e.code LIMIT ?""", corp, like);
        // Things that ride on or inside something else resolve to their carrier.
        add(hits, "Serial", q, """
            SELECT s.serial_number AS key, 'on pallet ' || v.lpn || ' · ' || i.sku::text AS detail, v.lpn AS extra
            FROM inventory_serial s JOIN inventory v ON v.inventory_id = s.inventory_id JOIN item i ON i.item_id = v.item_id
            WHERE v.corporation_id = ? AND s.serial_number ILIKE ? ORDER BY (upper(s.serial_number) = upper(?)) DESC, s.serial_number LIMIT ?""", corp, like);
        add(hits, "Container", q, """
            SELECT COALESCE(ac.inducted_lpn, ct.barcode) AS key, 'position ' || ac.cart_position || ' on ' || a.assignment_number || ' · ' || o.order_number AS detail, a.assignment_number AS extra
            FROM assignment_container ac JOIN assignment a ON a.assignment_id = ac.assignment_id JOIN zone_order z ON z.zone_order_id = ac.zone_order_id
            JOIN customer_order o ON o.order_id = z.order_id LEFT JOIN container ct ON ct.container_id = ac.container_id
            WHERE a.corporation_id = ? AND COALESCE(ac.inducted_lpn, ct.barcode) ILIKE ? ORDER BY (upper(COALESCE(ac.inducted_lpn, ct.barcode)) = upper(?)) DESC, a.created_at DESC LIMIT ?""", corp, like);

        hits.sort(Comparator.comparing((Hit h) -> !h.exact()));   // stable: exact first, kinds keep their order
        return hits;
    }

    /** The single thing a query names, if it names exactly one. */
    public Optional<Hit> only(List<Hit> hits) {
        if (hits.size() == 1) return Optional.of(hits.get(0));
        List<Hit> exact = hits.stream().filter(Hit::exact).toList();
        return exact.size() == 1 ? Optional.of(exact.get(0)) : Optional.empty();
    }

    /** Runs one kind's query. The SQL takes (corp, like[, like], q, limit) and returns key, detail, extra. */
    private void add(List<Hit> hits, String kind, String q, String sql, UUID corp, String... likes) {
        List<Object> args = new ArrayList<>();
        args.add(corp); args.addAll(Arrays.asList(likes)); args.add(q); args.add(PER_KIND);
        for (Map<String, Object> r : jdbc.queryForList(sql, args.toArray())) {
            String key = String.valueOf(r.get("key")), extra = (String) r.get("extra");
            String url = switch (kind) {
                case "Pallet" -> go.pallet(key);       case "Location" -> go.slot(key);       case "Item" -> go.item(key);
                case "Lot" -> go.lot(extra, key);      case "Order" -> go.order(key);         case "Customer" -> go.customer(key);
                case "Wave" -> go.wave(key);           case "Assignment" -> go.assignment(key); case "Manifest" -> go.manifest(key);
                case "Shipment" -> go.shipment(key);   case "Operator" -> go.operator(extra);  case "Equipment" -> go.equipment(key);
                case "Serial" -> go.pallet(extra);     default -> go.assignment(extra);
            };
            hits.add(new Hit(kind, kind, key, (String) r.get("detail"), url, key.equalsIgnoreCase(q)));
        }
    }
}
