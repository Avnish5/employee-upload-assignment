package com.example.employees.upload;

public enum UploadStatus {
    QUEUED, PROCESSING, COMPLETED, FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
