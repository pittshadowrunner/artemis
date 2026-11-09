package com.artemis.wms.masterdata;

import com.artemis.wms.platform.BulkResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.platform.Values.*;

/** Items, locations, customers, equipment, containers. Bulk endpoints are idempotent upserts. */
@RestController
@RequestMapping("/api/v1")
public class MasterDataController {
    private final ItemService items;
    private final LocationService locations;
    private final CustomerService customers;
    private final EquipmentService equipment;

    public MasterDataController(ItemService items, LocationService locations, CustomerService customers, EquipmentService equipment) {
        this.items = items; this.locations = locations; this.customers = customers; this.equipment = equipment;
    }

    // ---- items
    @GetMapping("/items") public List<Map<String, Object>> items(@RequestParam(required = false) String q, @RequestParam(required = false) String tempZone) { return items.list(q, upper(tempZone)); }
    @GetMapping("/items/{sku}") public Map<String, Object> item(@PathVariable String sku) { return items.get(sku); }
    @PostMapping("/items/bulk") public BulkResult itemsBulk(@RequestBody List<Map<String, Object>> body) { return items.bulk(body); }
    @PostMapping(value = "/items/upload", consumes = "multipart/form-data") public BulkResult itemsUpload(@RequestParam MultipartFile file) { return items.upload(file); }

    // ---- locations
    @GetMapping("/sites/{siteId}/locations") public List<Map<String, Object>> locations(@PathVariable UUID siteId, @RequestParam(required = false) String type, @RequestParam(required = false) String q) { return locations.list(siteId, upper(type), q); }
    @GetMapping("/sites/{siteId}/locations/{code}") public Map<String, Object> location(@PathVariable UUID siteId, @PathVariable String code) { return locations.get(siteId, code); }
    @PostMapping("/sites/{siteId}/locations/bulk") public BulkResult locationsBulk(@PathVariable UUID siteId, @RequestBody List<Map<String, Object>> body) { return locations.bulk(siteId, body); }
    @PostMapping("/sites/{siteId}/locations/generate") public BulkResult generate(@PathVariable UUID siteId, @RequestBody Map<String, Object> spec) { return locations.generate(siteId, spec); }
    @PostMapping(value = "/sites/{siteId}/locations/upload", consumes = "multipart/form-data") public BulkResult locationsUpload(@PathVariable UUID siteId, @RequestParam MultipartFile file) { return locations.upload(siteId, file); }

    // ---- customers
    @GetMapping("/customers") public List<Map<String, Object>> customers(@RequestParam(required = false) String q) { return customers.list(q); }
    @GetMapping("/customers/{code}") public Map<String, Object> customer(@PathVariable String code) { return customers.get(code); }
    @PostMapping("/customers/bulk") public BulkResult customersBulk(@RequestBody List<Map<String, Object>> body) { return customers.bulk(body); }

    // ---- equipment & containers
    @GetMapping("/sites/{siteId}/equipment") public List<Map<String, Object>> equipment(@PathVariable UUID siteId) { return equipment.list(siteId); }
    @PostMapping("/sites/{siteId}/equipment/bulk") public BulkResult equipmentBulk(@PathVariable UUID siteId, @RequestBody List<Map<String, Object>> body) { return equipment.bulkEquipment(siteId, body); }
    @PostMapping("/sites/{siteId}/containers/bulk") public BulkResult containersBulk(@PathVariable UUID siteId, @RequestBody List<Map<String, Object>> body) { return equipment.bulkContainers(siteId, body); }
    @PostMapping("/equipment/{id}/checkout") public Map<String, Object> checkout(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) { equipment.checkout(id, b == null ? null : uuid(b.get("userId"))); return Map.of("equipmentId", id, "status", "CHECKED_OUT"); }
    @PostMapping("/equipment/{id}/checkin") public Map<String, Object> checkin(@PathVariable UUID id) { equipment.checkin(id); return Map.of("equipmentId", id, "status", "CHECKED_IN"); }
}
