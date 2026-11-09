package com.artemis.wms.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Partial-success result: a 5,000-row file with three bad rows loads 4,997
 * and reports exactly which three failed and why.
 */
public class BulkResult {
    public int created;
    public int updated;
    public int skipped;
    public List<RowError> errors = new ArrayList<>();

    public record RowError(int row, String identifier, String message) {}

    public void created() { created++; }
    public void updated() { updated++; }
    public void skipped() { skipped++; }
    public void error(int row, String id, String message) { errors.add(new RowError(row, id, message)); }
    public int errorCount() { return errors.size(); }
    public boolean ok() { return errors.isEmpty(); }
}
