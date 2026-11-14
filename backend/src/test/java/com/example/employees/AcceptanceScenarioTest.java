package com.example.employees;

import com.example.employees.upload.UploadStatus;
import com.example.employees.upload.UploadView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replays the expected outcomes end to end with the real provided data files:
 * preload → upload-1 → upload-2-corrected → upload-2-corrected again.
 */
class AcceptanceScenarioTest extends IntegrationTestBase {

    @BeforeEach
    void reset() throws Exception {
        resetToPreloadedState();
    }

    @Test
    void preloadLoadsFiftyEmployeesAndIsIdempotent() throws Exception {
        assertThat(employeeCount()).isEqualTo(50);

        // A second preload (= app restart) must not duplicate anything.
        UploadView again = uploadService.preload(DATA.resolve("preloaded-employees.csv"));
        assertThat(again.insertedRows()).isZero();
        assertThat(again.skippedRows()).isEqualTo(50);
        assertThat(employeeCount()).isEqualTo(50);
    }

    @Test
    void uploadThenCorrectedReuploadMatchesSpec() throws Exception {
        // --- upload-1: 480 inserted, 5 already preloaded, 15 rejected ---
        UploadView u1 = importSync(DATA.resolve("upload-1.csv"));
        assertThat(u1.status()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(u1.totalRows()).isEqualTo(500);
        assertThat(u1.processedRows()).isEqualTo(500);
        assertThat(u1.insertedRows()).isEqualTo(480);
        assertThat(u1.skippedRows()).isEqualTo(5);
        assertThat(u1.rejectedRows()).isEqualTo(15);
        assertThat(employeeCount()).isEqualTo(530);

        Map<Integer, String> expected = new LinkedHashMap<>();
        expected.put(101, "REQUIRED");          // empty mobile
        expected.put(109, "CONFLICT");          // near-duplicate of row 11 (mobile typo)
        expected.put(116, "INVALID_FORMAT");    // 17/06/1992
        expected.put(193, "CONFLICT");          // near-duplicate of row 118
        expected.put(208, "DUPLICATE_IN_FILE"); // copy of row 54
        expected.put(215, "INVALID_DATE");      // 31-02-1990
        expected.put(295, "REQUIRED");          // empty first_name
        expected.put(353, "INVALID_FORMAT");    // 17-Jun-2023
        expected.put(369, "REQUIRED");          // empty dob
        expected.put(376, "DUPLICATE_IN_FILE"); // copy of row 72
        expected.put(426, "COLUMN_COUNT");      // 8 fields
        expected.put(429, "CONFLICT");          // near-duplicate of row 256
        expected.put(430, "DUPLICATE_IN_FILE"); // copy of row 231
        expected.put(469, "DUPLICATE_IN_FILE"); // copy of row 460
        expected.put(499, "INVALID_DATE");      // 45-13-2023
        assertThat(errorCodesByRow(u1.id())).containsExactlyEntriesOf(expected);

        assertThat(errorMessage(u1.id(), 109))
                .isEqualTo("Matches row 11 of this file on name+dob, email; "
                        + "differs in: mobile (6148179048 vs 6148179548)");
        assertThat(errorMessage(u1.id(), 208)).isEqualTo("Duplicate of row 54 in this file");

        // Padded values (rows 181, 451) were trimmed and accepted.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE mobile = '7388606964'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE first_name = 'Farida' AND last_name = 'Njoroge'", Integer.class))
                .isEqualTo(1);

        // --- upload-2-corrected: only the 8 fixed rows are new; the 3 near-duplicates still conflict ---
        UploadView u2 = importSync(DATA.resolve("upload-2-corrected.csv"));
        assertThat(u2.status()).isEqualTo(UploadStatus.COMPLETED);
        assertThat(u2.insertedRows()).isEqualTo(8);
        assertThat(u2.skippedRows()).isEqualTo(485);
        assertThat(u2.rejectedRows()).isEqualTo(3);
        assertThat(errorCodesByRow(u2.id())).containsExactlyInAnyOrderEntriesOf(Map.of(
                108, "CONFLICT", 192, "CONFLICT", 427, "CONFLICT"));
        // Row 11 of the corrected file is skipped (already loaded), so row 108 is reported against it:
        // the admin is pointed at the row in the file they are editing, and told it is already loaded.
        long fatimaId = jdbc.queryForObject("SELECT id FROM employee WHERE mobile = '6148179048'", Long.class);
        assertThat(errorMessage(u2.id(), 108)).isEqualTo(
                "Matches row 11 of this file (already in the system as employee #" + fatimaId + ") "
                        + "on name+dob, email; differs in: mobile (6148179048 vs 6148179548)");
        assertThat(employeeCount()).isEqualTo(538);

        // --- same corrected file again: fully idempotent ---
        UploadView u3 = importSync(DATA.resolve("upload-2-corrected.csv"));
        assertThat(u3.insertedRows()).isZero();
        assertThat(u3.skippedRows()).isEqualTo(493);
        assertThat(u3.rejectedRows()).isEqualTo(3);
        assertThat(employeeCount()).isEqualTo(538);

        // --- no person, mobile or email appears twice ---
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT mobile FROM employee GROUP BY mobile HAVING COUNT(*) > 1) d",
                Integer.class)).isZero();
    }

    private Map<Integer, String> errorCodesByRow(long uploadId) {
        Map<Integer, String> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT row_num, error_codes FROM upload_error WHERE upload_id = ? ORDER BY row_num", uploadId);
        rows.forEach(r -> result.put(((Number) r.get("row_num")).intValue(), (String) r.get("error_codes")));
        return result;
    }

    private String errorMessage(long uploadId, int row) {
        return jdbc.queryForObject("SELECT error_messages FROM upload_error WHERE upload_id = ? AND row_num = ?",
                String.class, uploadId, row);
    }
}
