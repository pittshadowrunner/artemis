package com.artemis.wms.replen;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.artemis.wms.platform.Values.*;

@RestController
@RequestMapping("/api/v1")
public class ReplenController {
    private final ReplenService replen;
    public ReplenController(ReplenService replen) { this.replen = replen; }

    @GetMapping("/sites/{siteId}/replen/pressure") public List<Map<String, Object>> pressure(@PathVariable UUID siteId) { return replen.pressure(siteId); }
    @PostMapping("/sites/{siteId}/replen/run") public Map<String, Object> run(@PathVariable UUID siteId) { return replen.run(siteId); }
    /** {checkDigits} */
    @PostMapping("/replen/tasks/{taskId}/complete") public Map<String, Object> complete(@PathVariable UUID taskId, @RequestBody Map<String, Object> b) { return replen.complete(taskId, str(b.get("checkDigits"))); }
}
