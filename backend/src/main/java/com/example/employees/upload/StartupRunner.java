package com.example.employees.upload;

import com.example.employees.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** On startup: fail uploads interrupted by the previous shutdown, then load the preload file. */
@Component
public class StartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    private final UploadRepository uploads;
    private final UploadService uploadService;
    private final AppProperties props;

    public StartupRunner(UploadRepository uploads, UploadService uploadService, AppProperties props) {
        this.uploads = uploads;
        this.uploadService = uploadService;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int interrupted = uploads.failInterrupted();
        if (interrupted > 0) {
            log.warn("Marked {} interrupted upload(s) as FAILED", interrupted);
        }
        Path preload = props.preloadPath();
        if (preload == null || !Files.isRegularFile(preload)) {
            throw new IllegalStateException("Preload file not found: " + preload
                    + " (set PRELOAD_PATH or app.preload-path)");
        }
        UploadView result = uploadService.preload(preload);
        log.info("Preload {}: status={} inserted={} skipped={} rejected={}", preload, result.status(),
                result.insertedRows(), result.skippedRows(), result.rejectedRows());
    }
}
