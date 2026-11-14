package com.example.employees;

import com.example.employees.upload.UploadStatus;
import com.example.employees.upload.UploadView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 50,000-row upload (the largest expected file) must finish in under 60 s.
 * Excluded from the default build; run with: ./mvnw test -Pperf
 */
@Tag("perf")
class PerformanceTest extends IntegrationTestBase {

    static final int ROWS = 50_000;

    @Test
    void fiftyThousandRowsUnderOneMinute(@TempDir Path dir) throws Exception {
        resetToPreloadedState();
        Path file = dir.resolve("big.csv");
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("first_name,last_name,dob,gender,mobile,email,department,designation,date_of_joining\r\n");
            for (int i = 0; i < ROWS; i++) {
                // Unique person (name + dob), mobile and email per row; every 100th row has a bad date.
                String dob = String.format("%02d-%02d-%d", 1 + i % 28, 1 + (i / 28) % 12, 1960 + (i / 336) % 40);
                String doj = i % 100 == 99 ? "31-02-2020" : "01-06-2021";
                w.write(String.format("Person,Num%s,%s,%s,5%09d,p%d@example.org,Revenue,Clerk,%s\r\n",
                        letters(i), dob, i % 2 == 0 ? "F" : "M", i, i, doj));
            }
        }

        long start = System.nanoTime();
        UploadView result = importSync(file);
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        System.out.printf("PERF: %d rows in %d ms (%.0f rows/s)%n", ROWS, took.toMillis(),
                ROWS / (took.toMillis() / 1000.0));

        assertThat(result.status()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(result.insertedRows()).isEqualTo(ROWS - ROWS / 100);
        assertThat(result.rejectedRows()).isEqualTo(ROWS / 100);
        assertThat(took).isLessThan(Duration.ofSeconds(60));

        // Re-upload of the same 50k file: everything skipped, nothing duplicated.
        long start2 = System.nanoTime();
        UploadView again = importSync(file);
        System.out.printf("PERF: re-upload in %d ms%n", (System.nanoTime() - start2) / 1_000_000);
        assertThat(again.insertedRows()).isZero();
        assertThat(again.skippedRows()).isEqualTo(ROWS - ROWS / 100);
        assertThat(employeeCount()).isEqualTo(50 + ROWS - ROWS / 100);
    }

    /** Base-26 letters so last names stay valid (names may not contain digits). */
    private static String letters(int n) {
        StringBuilder sb = new StringBuilder();
        do {
            sb.append((char) ('a' + n % 26));
            n /= 26;
        } while (n > 0);
        return sb.toString();
    }
}
