package com.example.employees;

import com.example.employees.importer.CsvFiles;
import com.example.employees.importer.ImportService;
import com.example.employees.upload.UploadRepository;
import com.example.employees.upload.UploadService;
import com.example.employees.upload.UploadSource;
import com.example.employees.upload.UploadView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Real MySQL via Testcontainers (one container shared by all integration tests).
 * "Today" is fixed so date-range rules are deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
public abstract class IntegrationTestBase {

    public static final Path DATA = Path.of("..", "data");

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static {
        MYSQL.start();
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-11-14T06:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected UploadRepository uploads;
    @Autowired
    protected UploadService uploadService;
    @Autowired
    protected ImportService importService;

    /** Empties all tables and re-runs the startup preload, so each test starts from the "first start" state. */
    protected void resetToPreloadedState() throws Exception {
        jdbc.update("DELETE FROM upload_error");
        jdbc.update("DELETE FROM employee");
        jdbc.update("DELETE FROM upload");
        uploadService.preload(DATA.resolve("preloaded-employees.csv"));
    }

    /** Runs a file through the import pipeline synchronously (the worker-thread path is tested via the API). */
    protected UploadView importSync(Path file) throws Exception {
        int rows = CsvFiles.inspect(file).dataRows();
        long id = uploads.create(file.getFileName().toString(), UploadSource.USER, rows);
        importService.importFile(id, file);
        return uploads.findById(id).orElseThrow();
    }

    protected long employeeCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM employee", Long.class);
        return n == null ? 0 : n;
    }
}
