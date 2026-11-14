package com.example.employees.upload;

import java.time.Instant;

public record UploadView(
        long id,
        String filename,
        UploadSource source,
        UploadStatus status,
        String errorMessage,
        int totalRows,
        int processedRows,
        int insertedRows,
        int skippedRows,
        int rejectedRows,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {
}
