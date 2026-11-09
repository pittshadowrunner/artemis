package com.artemis.wms.inventory;

import com.artemis.wms.platform.BulkResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class InventoryController {
    private final InventoryService inventory;
    public InventoryController(InventoryService inventory) { this.inventory = inventory; }

    @GetMapping("/sites/{siteId}/inventory")
    public List<Map<String, Object>> list(@PathVariable UUID siteId, @RequestParam(required = false) String sku, @RequestParam(required = false) String status) {
        return inventory.list(siteId, sku, status == null ? null : status.toUpperCase());
    }
    @PostMapping("/sites/{siteId}/inventory/opening-balances")
    public BulkResult load(@PathVariable UUID siteId, @RequestBody List<Map<String, Object>> body) { return inventory.openingBalances(siteId, body); }
    @PostMapping(value = "/sites/{siteId}/inventory/upload", consumes = "multipart/form-data")
    public BulkResult upload(@PathVariable UUID siteId, @RequestParam MultipartFile file) { return inventory.upload(siteId, file); }
    @GetMapping("/lpn/{lpn}")
    public Map<String, Object> pallet(@PathVariable String lpn) { return inventory.pallet(lpn); }
}
