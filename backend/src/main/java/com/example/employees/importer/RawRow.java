package com.example.employees.importer;

import java.util.List;

/**
 * One data row exactly as it appeared in the file.
 *
 * @param rowNumber   spreadsheet row number (header = 1, first data row = 2), as in Excel
 * @param values      raw, untrimmed values. In canonical {@link Column} order when the field count is right;
 *                    in file order when it is wrong (they cannot be mapped to columns safely)
 * @param fieldCount  number of fields actually present in the row
 * @param expectedFieldCount number of fields in the header
 */
public record RawRow(int rowNumber, List<String> values, int fieldCount, int expectedFieldCount) {

    public boolean hasColumnCountMismatch() {
        return fieldCount != expectedFieldCount;
    }

    /** Raw value for a column; only valid when there is no column-count mismatch. */
    public String get(Column column) {
        return values.get(column.ordinal());
    }

    /** Convenience for tests and callers that already have values in canonical order. */
    public static RawRow of(int rowNumber, String... canonicalValues) {
        return new RawRow(rowNumber, List.of(canonicalValues), canonicalValues.length, Column.values().length);
    }
}
