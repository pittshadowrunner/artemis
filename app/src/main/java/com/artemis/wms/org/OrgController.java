package com.artemis.wms.org;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.platform.Values.*;

@RestController
@RequestMapping("/api/v1/org")
public class OrgController {
    private final OrgService org;
    private final PolicyService policy;
    public OrgController(OrgService org, PolicyService policy) { this.org = org; this.policy = policy; }

    @GetMapping("/corporations")
    public List<Map<String, Object>> corporations() { return org.corporations(); }

    /** Sysadmin: {code, name, city?...} */
    @PostMapping("/corporations") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> corporation(@RequestBody Map<String, Object> b) {
        return Map.of("orgNodeId", org.createCorporation(str(b.get("code")), str(b.get("name")), b));
    }

    /** {level, parentId, code, name, addressLine1?, city?, ...} */
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> node(@RequestBody Map<String, Object> b) {
        return Map.of("orgNodeId", org.upsertNode(str(b.get("level")), uuid(b.get("parentId")), str(b.get("code")), str(b.get("name")), b));
    }

    @GetMapping("/sites")
    public List<Map<String, Object>> sites() { return org.sites(); }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) { return org.node(id); }

    @GetMapping("/{id}/tree")
    public Map<String, Object> tree(@PathVariable UUID id) { return org.tree(id); }

    @GetMapping("/{id}/policy")
    public Map<String, Object> policy(@PathVariable UUID id) {
        return Map.of("declared", policy.declared(id), "effective", policy.effective(id));
    }

    @PutMapping("/{id}/policy")
    public Map<String, Object> setPolicy(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        policy.set(id, body);
        return Map.of("declared", policy.declared(id), "effective", policy.effective(id));
    }
}
