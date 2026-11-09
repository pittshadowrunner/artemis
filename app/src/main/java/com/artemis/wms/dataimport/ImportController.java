package com.artemis.wms.dataimport;

import com.artemis.wms.platform.ApiException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.util.Map;

/**
 * POST /api/v1/import with a YAML (or JSON) body. `ops/artemis import file.yaml`
 * is a thin wrapper over this endpoint — no psql, no UUID hunting, no docker cp.
 */
@RestController
@RequestMapping("/api/v1/import")
public class ImportController {
    private final ImportService importer;
    private final YAMLMapper yaml = new YAMLMapper();
    private final JsonMapper json = new JsonMapper();
    public ImportController(ImportService importer) { this.importer = importer; }

    @PostMapping(consumes = {"application/yaml", "application/x-yaml", "text/yaml", "application/json", "text/plain"})
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(@RequestBody String body) {
        Map<String, Object> doc;
        try {
            String t = body.trim();
            doc = (t.startsWith("{") ? json : yaml).readValue(t, Map.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Document isn't valid YAML/JSON: " + e.getMessage());
        }
        if (doc == null || doc.isEmpty()) throw ApiException.badRequest("Empty document.");
        return importer.apply(doc);
    }
}
