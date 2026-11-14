package com.example.employees.upload;

import com.example.employees.config.AppProperties;
import com.example.employees.importer.CsvFiles;
import com.example.employees.importer.ImportService;
import com.example.employees.importer.InvalidFileException;
import com.example.employees.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final UploadRepository uploads;
    private final ImportService importService;
    private final ExecutorService importExecutor;
    private final Path uploadDir;

    public UploadService(UploadRepository uploads, ImportService importService, ExecutorService importExecutor,
                         AppProperties props) {
        this.uploads = uploads;
        this.importService = importService;
        this.importExecutor = importExecutor;
        this.uploadDir = props.uploadDir();
    }

    /**
     * Validates the file up front (file-level checks), stores it and queues it for the single worker.
     * Returns immediately with the QUEUED upload; the client polls for progress.
     */
    public UploadView submit(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "upload.csv" : Path.of(file.getOriginalFilename())
                .getFileName().toString();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw ApiException.badFile("Only .csv files are accepted (got '" + filename + "').");
        }
        if (file.isEmpty()) {
            throw ApiException.badFile("File is empty.");
        }
        Path stored = store(file);
        CsvFiles.Inspection inspection;
        try {
            inspection = CsvFiles.inspect(stored);
        } catch (InvalidFileException e) {
            deleteQuietly(stored);
            throw ApiException.badFile(e.getMessage());
        }
        long id = uploads.create(filename, UploadSource.USER, inspection.dataRows());
        importExecutor.submit(() -> {
            try {
                importService.importFile(id, stored);
            } finally {
                deleteQuietly(stored);
            }
        });
        return uploads.findById(id).orElseThrow();
    }

    /** Imports the preload file synchronously through the same pipeline as user uploads. */
    public UploadView preload(Path file) throws InvalidFileException {
        CsvFiles.Inspection inspection = CsvFiles.inspect(file);
        long id = uploads.create(file.getFileName().toString(), UploadSource.PRELOAD, inspection.dataRows());
        importService.importFile(id, file);
        return uploads.findById(id).orElseThrow();
    }

    private Path store(MultipartFile file) {
        try {
            Files.createDirectories(uploadDir);
            Path target = uploadDir.resolve(UUID.randomUUID() + ".csv");
            file.transferTo(target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store upload", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete {}", path, e);
        }
    }
}
