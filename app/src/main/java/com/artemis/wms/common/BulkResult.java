package com.artemis.wms.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Partial-success bulk load result. A 5,000-row file with three bad rows
 * loads 4,997 and reports exactly which three failed and why.
 */
public class BulkResult {
    public int createdCount;
    public int skipped;
    public int errorCount;
    public List<RowError> errors = new ArrayList<>();

    public boolean isCompleteSuccess() { return errorCount == 0; }

    public void created() { createdCount++; }
    public void skippedRow() { skipped++; }
    public void error(int row, String identifier, String message) {
        errorCount++;
        errors.add(new RowError(row, identifier, message));
    }

    public record RowError(int row, String identifier, String message) {}
}
