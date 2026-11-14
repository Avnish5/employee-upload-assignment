package com.example.employees.importer;

/** An employee already in the database, as seen by the deduplicator. */
public record ExistingEmployee(long id, long uploadId, String uploadSource, int sourceRow, NormalizedRow row) {

    public String describe() {
        String origin = "PRELOAD".equals(uploadSource)
                ? "preloaded, row " + sourceRow
                : "upload " + uploadId + ", row " + sourceRow;
        return "employee #" + id + " (" + origin + ")";
    }
}
