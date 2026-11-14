package com.example.employees.importer;

/** Row-level error codes that appear in the error report. */
public enum ErrorCode {
    COLUMN_COUNT,
    REQUIRED,
    INVALID_FORMAT,
    INVALID_DATE,
    DATE_RANGE,
    DUPLICATE_IN_FILE,
    CONFLICT
}
