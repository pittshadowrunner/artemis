package com.artemis.wms.web;

import com.artemis.wms.security.CapabilityService;
import com.artemis.wms.security.Capabilities;
import com.artemis.wms.security.TenantContext;
import com.artemis.wms.service.OrgService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/org")
public class OrgController {

    private final OrgService org;
    private final CapabilityService caps;

    public OrgController(OrgService org, CapabilityService caps) { this.org = org; this.caps = caps; }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        if ("CORPORATION".equals(body.get("level"))) {
            // Platform topology: sysadmin only. Tenants never mint tenants.
            caps.requireSysadmin(TenantContext.user());
        } else {
            caps.require(TenantContext.user(), null, Capabilities.ORG_MANAGE);
        }
        UUID id = org.create(body.get("level"),
                body.get("parentId") == null ? null : UUID.fromString(body.get("parentId")),
                body.get("code"), body.get("name"), body);
        return ResponseEntity.ok(Map.of("orgNodeId", id));
    }

    @GetMapping("/{id}/tree")
    public Map<String, Object> tree(@PathVariable UUID id) { return org.tree(id); }
}
