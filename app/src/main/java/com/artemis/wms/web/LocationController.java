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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public LocationController(LocationService locations, CapabilityService caps,
                              org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.locations = locations; this.caps = caps;
    }

    @org.springframework.web.bind.annotation.GetMapping
    public java.util.List<java.util.Map<String, Object>> find(
            @org.springframework.web.bind.annotation.RequestParam java.util.UUID siteId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String code) {
        return jdbc.queryForList(
            "SELECT location_id, code, loc_type::text AS loc_type, temp_zone::text AS temp_zone, "
            + "check_digits, pick_sequence FROM location WHERE site_id = ? "
            + (code == null ? "" : "AND code = ? ") + "ORDER BY code LIMIT 50",
            code == null ? new Object[]{siteId} : new Object[]{siteId, code});
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
