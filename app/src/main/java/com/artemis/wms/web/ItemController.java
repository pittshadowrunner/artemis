package com.artemis.wms.web;

import com.artemis.wms.common.BulkResult;
import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.ItemService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemService items;
    private final CapabilityService caps;

    public ItemController(ItemService items, CapabilityService caps) { this.items = items; this.caps = caps; }

    @PostMapping("/bulk")
    @SuppressWarnings("unchecked")
    public BulkResult bulk(@RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.ITEM_MANAGE);
        return items.bulk((List<Map<String, Object>>) body.get("items"));
    }

    @PostMapping("/upload")
    public BulkResult upload(@RequestParam("file") MultipartFile file) {
        caps.require(TenantContext.user(), null, Capabilities.ITEM_MANAGE);
        return items.upload(file);
    }
}
