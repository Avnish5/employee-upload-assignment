package com.example.employees.importer;

import com.example.employees.config.AppProperties;
import com.example.employees.upload.UploadRepository;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Runs one upload through the pipeline: stream → map header → validate → dedup → batch write.
 * Each chunk (default 1,000 rows) is one transaction that also advances the progress counters,
 * so the UI sees steady progress and a crash never leaves a chunk half-written.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final RowValidator validator;
    private final ImportRepository importRepository;
    private final UploadRepository uploads;
    private final TransactionTemplate tx;
    private final int chunkSize;

    public ImportService(RowValidator validator, ImportRepository importRepository, UploadRepository uploads,
                         TransactionTemplate tx, AppProperties props) {
        this.validator = validator;
        this.importRepository = importRepository;
        this.uploads = uploads;
        this.tx = tx;
        this.chunkSize = props.chunkSize() > 0 ? props.chunkSize() : 1000;
    }

    /** Processes the file synchronously. Never throws: failures are recorded on the upload as FAILED. */
    public void importFile(long uploadId, Path file) {
        long started = System.nanoTime();
        uploads.markProcessing(uploadId);
        try (CSVParser parser = CsvFiles.open(file)) {
            Iterator<CSVRecord> records = parser.iterator();
            HeaderMapping mapping = HeaderMapping.from(records.next());
            Deduplicator dedup = new Deduplicator();
            List<RawRow> chunk = new ArrayList<>(chunkSize);
            int rowNumber = 1; // header
            while (records.hasNext()) {
                CSVRecord record = records.next();
                rowNumber++;
                if (CsvFiles.isBlank(record)) {
                    continue; // still advances rowNumber so later rows keep their spreadsheet numbers
                }
                chunk.add(mapping.toRawRow(rowNumber, record));
                if (chunk.size() == chunkSize) {
                    processChunk(uploadId, chunk, dedup);
                    chunk.clear();
                }
            }
            processChunk(uploadId, chunk, dedup);
            uploads.markCompleted(uploadId);
            log.info("Upload {} completed in {} ms", uploadId, (System.nanoTime() - started) / 1_000_000);
        } catch (Exception e) {
            log.error("Upload {} failed", uploadId, e);
            uploads.markFailed(uploadId, "Processing failed: " + e.getMessage());
        }
    }

    private void processChunk(long uploadId, List<RawRow> chunk, Deduplicator dedup) {
        if (chunk.isEmpty()) {
            return;
        }
        List<ImportRepository.RejectedRow> rejected = new ArrayList<>();
        List<Deduplicator.Candidate> valid = new ArrayList<>();
        for (RawRow raw : chunk) {
            switch (validator.validate(raw)) {
                case RowValidator.Valid v -> valid.add(new Deduplicator.Candidate(raw, v.row()));
                case RowValidator.Invalid inv -> rejected.add(new ImportRepository.RejectedRow(raw, inv.errors()));
            }
        }

        List<Deduplicator.Candidate> inserts = new ArrayList<>();
        int skipped = 0;
        for (Deduplicator.Outcome outcome : dedup.classify(valid, importRepository)) {
            switch (outcome) {
                case Deduplicator.Insert i -> inserts.add(i.candidate());
                case Deduplicator.Skip s -> skipped++;
                case Deduplicator.Reject r -> rejected.add(
                        new ImportRepository.RejectedRow(r.candidate().raw(), List.of(r.error())));
            }
        }
        // Keep the error report in file order.
        rejected.sort((a, b) -> Integer.compare(a.raw().rowNumber(), b.raw().rowNumber()));

        int skippedCount = skipped;
        tx.executeWithoutResult(status -> {
            importRepository.insertEmployees(uploadId, inserts);
            importRepository.insertErrors(uploadId, rejected);
            uploads.addProgress(uploadId, chunk.size(), inserts.size(), skippedCount, rejected.size());
        });
    }
}
