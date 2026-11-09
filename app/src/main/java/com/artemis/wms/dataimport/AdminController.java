package com.artemis.wms.dataimport;

import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService admin;
    public AdminController(AdminService admin) { this.admin = admin; }

    /** DELETE /api/v1/admin/tenants/{id}/data?scope=operations|all&confirm=<corp code> */
    @DeleteMapping("/tenants/{id}/data")
    public Map<String, Object> wipe(@PathVariable UUID id, @RequestParam(defaultValue = "operations") String scope, @RequestParam String confirm) {
        return admin.wipe(id, scope, confirm);
    }
}
