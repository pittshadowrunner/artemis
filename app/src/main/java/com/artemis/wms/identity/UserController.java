package com.artemis.wms.identity;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.platform.Values.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService users;
    private final AccessService access;
    public UserController(UserService users, AccessService access) { this.users = users; this.access = access; }

    @GetMapping("/me")
    public Map<String, Object> me() { return users.me(); }

    /** Operators of the current tenant with their grants and trained workflows. */
    @GetMapping
    public List<Map<String, Object>> list() { return users.operators(access.requireTenant()); }

    /** {email, displayName, password?, workflows?: [...]} — create or update. */
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upsert(@RequestBody Map<String, Object> body) {
        UUID id = users.upsert(access.requireTenant(), str(body.get("email")), str(body.get("displayName")),
            str(body.get("password")), strings(body.get("workflows")));
        return Map.of("userId", id);
    }

    /** {email, orgNodeId, role} */
    @PostMapping("/grants") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> grant(@RequestBody Map<String, Object> body) {
        UUID user = users.idByEmail(str(body.get("email")));
        users.grant(user, uuid(body.get("orgNodeId")), str(body.get("role")));
        return Map.of("userId", user, "orgNodeId", body.get("orgNodeId"), "role", body.get("role"));
    }
}
