package com.artemis.wms.web;

import com.artemis.wms.common.BulkResult;
import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.LocationService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/locations")
public class LocationController {

    private final LocationService locations;
    private final CapabilityService caps;

    public LocationController(LocationService locations, CapabilityService caps) {
        this.locations = locations; this.caps = caps;
    }

    @PostMapping("/bulk")
    @SuppressWarnings("unchecked")
    public BulkResult bulk(@RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.LOCATION_MANAGE);
        return locations.bulk(UUID.fromString(body.get("siteId").toString()),
                Boolean.TRUE.equals(body.get("skipExisting")),
                (List<Map<String, Object>>) body.get("locations"));
    }

    @PostMapping("/generate")
    public BulkResult generate(@RequestBody Map<String, Object> body) {
        caps.require(TenantContext.user(), null, Capabilities.LOCATION_MANAGE);
        return locations.generate(body);
    }

    @PostMapping("/upload")
    public BulkResult upload(@RequestParam UUID siteId, @RequestParam("file") MultipartFile file) {
        caps.require(TenantContext.user(), null, Capabilities.LOCATION_MANAGE);
        return locations.upload(siteId, file);
    }
}
