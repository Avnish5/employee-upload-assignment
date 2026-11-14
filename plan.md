# Plan: Bulk Employee Upload

**Time box:** 3 hours · **Requirements and decisions:** [`spec.md`](spec.md) · **Run/test instructions:** [`README.md`](README.md)

## Repo layout

```
docker-compose.yml   README.md   spec.md   plan.md   ai-log.md
data/        the 4 provided files, copied unchanged
backend/     Spring Boot 3.5, Java 21, Maven wrapper, Flyway, JdbcTemplate, Apache Commons CSV
frontend/    index.html + app.js + styles.css, served by nginx; /api/* proxied to backend
```

Compose services:

| Service | What it is |
|---|---|
| `db` | `mysql:8.4` with a TCP healthcheck; data in the `db-data` volume |
| `backend` | Multi-stage Maven → JRE build; starts only once `db` is healthy; `data/` mounted read-only |
| `frontend` | nginx on **localhost:8080**, proxying `/api/*` to `backend` |

## Milestones

| # | Milestone | Done when |
|---|---|---|
| M1 | Skeleton + Compose + schema + preload | `docker compose up` from clean → list API returns 50 employees; restart → still 50 |
| M2 | Import pipeline: stream, map header, normalize, validate, dedup, batch insert, record errors, progress | Integration test passes the section 2.3 numbers |
| M3 | Upload / status / error-report / employee APIs | curl walkthrough works; report rows match spec section 2.1 |
| M4 | Frontend: upload + progress bar + report download, employee list with paging | Manual run of the brief's checklist in a browser |
| M5 | Tests: validator unit tests, end-to-end integration test, 50k-row performance check | `./mvnw test` green; 50k rows < 60 s |
| M6 | README, ai-log, clean-checkout verification | Fresh clone + `docker compose up` passes the checklist |
| — | Stretch: upload history, search/filter | Only if M1–M6 are done |

After each milestone I run it for real (compose or tests) before starting the next one.

### What changed during the build

- Preload moved from M1 to M2, because it reuses the import pipeline.
- The stretch goals got built (upload history in M3, search/department filter in M4), since the must-haves were already passing.
- All section 2.3 numbers matched on the first run; 50k rows load in ~9 s in tests and ~3 s in Docker.

## Data model (Flyway `V1__init.sql`)

Summary. See the migration file for exact types and constraint names.

```sql
upload (
  id BIGINT PK AUTO_INCREMENT, filename VARCHAR(255),
  source VARCHAR(16),                   -- USER | PRELOAD
  status VARCHAR(16),                   -- QUEUED | PROCESSING | COMPLETED | FAILED
  error_message VARCHAR(1000) NULL,
  total_rows, processed_rows, inserted_rows, skipped_rows, rejected_rows INT DEFAULT 0,
  created_at, started_at, finished_at DATETIME(3)
)

employee (
  id BIGINT PK AUTO_INCREMENT,
  first_name VARCHAR(100), last_name VARCHAR(100), dob DATE, gender CHAR(1),
  mobile CHAR(10), email VARCHAR(254) NULL utf8mb4_bin,
  department VARCHAR(100), designation VARCHAR(100), date_of_joining DATE,
  name_dob_key VARCHAR(220) utf8mb4_bin,  -- lower(first)|lower(last)|yyyy-mm-dd
  upload_id BIGINT FK, source_row INT, created_at DATETIME(3),
  UNIQUE (name_dob_key), UNIQUE (mobile), UNIQUE (email),
  INDEX (last_name, first_name, id), INDEX (department)
)

upload_error (
  id BIGINT PK AUTO_INCREMENT, upload_id BIGINT FK,
  row_num INT,                          -- not row_number: reserved word in MySQL 8
  error_codes VARCHAR(200), error_messages VARCHAR(2000), raw_values JSON,
  INDEX (upload_id, row_num)
)
```

`email` and `name_dob_key` use a binary collation so that DB uniqueness matches the application's exact comparison. The default accent-insensitive collation would treat `José` and `Jose` as equal.

## Backend design

Packages under `com.example.employees`:

| Package | Classes | Responsibility |
|---|---|---|
| `upload` | `UploadController`, `UploadService` | Accept the multipart file and save it to the upload directory. Inspect it (UTF-8, header, row count, so progress has a total from the start). Create `upload(QUEUED)`, submit it to the single-thread `ExecutorService` (FIFO), and return `202`. |
| `importer` | `CsvFiles`, `HeaderMapping` | Strict UTF-8 reading (BOM tolerated, CRLF/LF), header matched by name, blank lines kept so row numbers match Excel. |
| `importer` | `RowValidator` → `NormalizedRow` | No DB access, so it's easy to unit test. Normalizes the row and collects all of its errors. |
| `importer` | `Deduplicator`, `ImportService`, `ImportRepository` | Per chunk of 1,000 valid rows: see below. |
| `upload` | `ErrorReportWriter` | Streams the error CSV from `upload_error`, with formula-injection protection. |
| `upload` | `StartupRunner` | On startup: marks uploads left `QUEUED`/`PROCESSING` as `FAILED` ("interrupted, please re-upload"), then imports `data/preloaded-employees.csv` through the same pipeline, synchronously. |
| `employee` | `EmployeeController`, `EmployeeRepository` | Paged, searchable employee list. |
| `web` | `ApiExceptionHandler` | Consistent `{error, message}` error bodies. |
| `config` | `AppConfig`, `AppProperties` | Executor, `Clock` bean for "today" (deterministic tests), chunk size, paths. |

Per-chunk processing:

1. Check within the file using in-memory maps of keys seen earlier in this upload (key → row number + normalized row).
2. Check the DB with 3 batched `SELECT … WHERE key IN (…)` queries.
3. Classify each row as insert / skip / duplicate-in-file / conflict.
4. Run `batchUpdate` for inserts and errors in one transaction, together with the progress counters.

## API

| Method | Path | Response |
|---|---|---|
| POST | `/api/uploads` (multipart `file`) | `202 {id, status}` · `400` for file-level errors |
| GET | `/api/uploads/{id}` | `{id, filename, status, totalRows, processedRows, insertedRows, skippedRows, rejectedRows, errorMessage}` |
| GET | `/api/uploads/{id}/errors.csv` | Streamed CSV (`Content-Disposition: attachment`) |
| GET | `/api/uploads?limit=20` *(stretch, built)* | Upload history, newest first |
| GET | `/api/employees?page=0&size=50&q=&department=` | `{items, page, size, total}`; `q` and `department` are the stretch search/filter |
| GET | `/api/employees/departments` *(stretch, built)* | Values for the department filter |

Errors return a consistent `{error, message}` JSON body (`@RestControllerAdvice`).

## Test strategy

| Layer | Test class | What | Tool |
|---|---|---|---|
| Unit | `RowValidatorTest` | One case per problem in the data (empty field, `17/06/1992`, `31-02-1990`, `17-Jun-2023`, `45-13-2023`, 8 columns, padded values, bad gender/mobile/email, future DOB, DOJ before DOB) | JUnit 5, parameterized |
| Unit | `DeduplicatorTest` | Exact dup in file, near-dup conflict, exact match to existing → skip, keys pointing at two different employees | JUnit 5 |
| Unit | `ErrorReportWriterTest` | Formula-injection prefix applied to `= + - @` values; ordinary values untouched | JUnit 5 |
| Integration | `AcceptanceScenarioTest` | Replays the real files. Inserted / skipped / rejected per step: preload → upload-1 (480/5/15, exact rejected row numbers) → upload-2 (8/485/3, rows 108/192/427) → upload-2 again (0/493/3) → total 538 | Spring Boot Test + Testcontainers MySQL |
| Integration | `ApiIntegrationTest` | Async upload over HTTP, error-report content and row numbers, file-level rejections, reordered columns and blank lines, paging | Spring Boot Test + Testcontainers MySQL |
| Performance | `PerformanceTest` | Generated 50k-row file processed in < 60 s, then idempotent re-upload | JUnit `@Tag("perf")`, run with `-Pperf` |
| Manual | — | The brief's submission checklist, from a clean checkout | Browser |

Commands (from `backend/`): `./mvnw test` (requires Docker for Testcontainers); `./mvnw test -Pperf` for the performance test.

## Risks

| Risk | Mitigation |
|---|---|
| Testcontainers needs Docker on the reviewer's machine. | Unit tests still run without it, and the README documents this. |
| A batch insert hits a MySQL unique constraint (should be impossible with a single worker). | The chunk's transaction rolls back and the upload is marked `FAILED`, instead of loading partial data without telling anyone. Re-upload is idempotent. |

## With another day

- Fuzzy duplicate detection (name similarity + same DOB), shown as "possible duplicate, please confirm".
- An update flow: show the diff for conflicting rows and let the admin approve changes.
- Non-blocking warnings (under-18 at joining, name/email mismatch).
- Auth + audit trail of who uploaded what.
- Server-Sent Events instead of polling for progress.
- Resumable processing from the last committed chunk.
