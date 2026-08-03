package com.artemis.wms.web;

import com.artemis.wms.common.BulkResult;
import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.InventoryService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService inventory;
    private final CapabilityService caps;

    public InventoryController(InventoryService inventory, CapabilityService caps) {
        this.inventory = inventory; this.caps = caps;
    }

    @PostMapping("/upload")
    @SuppressWarnings("unchecked")
    public BulkResult upload(@RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.INVENTORY_ADJUST);
        return inventory.load(UUID.fromString(body.get("siteId").toString()),
                Boolean.TRUE.equals(body.get("replaceExisting")),
                (List<Map<String, Object>>) body.get("records"));
    }

    @PostMapping("/upload-file")
    public BulkResult uploadFile(@RequestParam UUID siteId,
                                 @RequestParam(defaultValue = "false") boolean replaceExisting,
                                 @RequestParam("file") MultipartFile file) {
        caps.require(TenantContext.user(), null, Capabilities.INVENTORY_ADJUST);
        return inventory.uploadFile(siteId, replaceExisting, file);
    }
}
