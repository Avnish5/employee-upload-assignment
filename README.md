# Employee Bulk Upload

HR admins upload an employee CSV (500–50,000 rows), watch it process live, download a per-row error report numbered by the input file's rows, and re-upload a corrected file without creating duplicates.

**Stack:** Spring Boot 3.5 (Java 21) · MySQL 8.4 · plain HTML/JS on nginx · Docker Compose

Docs: [`spec.md`](spec.md) (decisions and why) · [`plan.md`](plan.md) (milestones, data model, API, tests) · [`ai-log.md`](ai-log.md) (how I used the AI) · [`ai-transcript.md`](ai-transcript.md) (full session transcript)

---

## Run it

```bash
docker compose up
```

Then open **http://localhost:8080**.

That's it. It builds the backend and frontend images, starts MySQL, applies the schema (Flyway), and loads `data/preloaded-employees.csv`. The first build downloads Maven dependencies and takes a few minutes; later starts take seconds.

| To… | Run |
|---|---|
| Stop | `Ctrl+C`, or `docker compose down` |
| Reset all data | `docker compose down -v` |
| Use another port (8080 busy) | Change `"8080:80"` in `docker-compose.yml` |

## Walk through the brief's checklist

| # | Action | Expected result | Employees |
|---|---|---|---|
| 1 | First start | Employee list shows the preloaded employees | **50** |
| 2 | Upload `data/upload-1.csv` | Progress bar, then **480 inserted · 5 skipped · 15 rejected** | **530** |
| 3 | Upload `data/upload-2-corrected.csv` | **8 inserted · 485 skipped · 3 rejected** (rows 108, 192, 427). The 8 fixed rows load; nothing is duplicated. | **538** |
| 4 | Upload the corrected file again | **0 inserted · 493 skipped · 3 rejected**. Re-upload is idempotent. | **538** |
| 5 | Restart the stack (`docker compose restart`) | Preload doesn't duplicate | **538** |

After step 2, "Download error report" gives a CSV whose `row_number` column is the row in the input file (header = row 1, as in Excel). The 15 rejected rows are 101, 109, 116, 193, 208, 215, 295, 353, 369, 376, 426, 429, 430, 469, 499.

> The 3 rejections on the corrected file are on purpose. Rows 108, 192 and 427 are the same person as rows 11, 117 and 255 (same name, DOB, email, department, designation and joining date) with a one-digit difference in the mobile number. The "corrected" file didn't fix them. If mobile were the identity key they'd load as 3 duplicate employees, so they're reported as conflicts instead. See [spec section 4](spec.md#4-employee-identity-d9).

## How it works

| Concern | Approach |
|---|---|
| Identity | Three unique keys: **name + DOB**, **mobile**, **email** (if present). Identical to an existing employee → skipped. Partial match → rejected as `CONFLICT` with the differing fields. Uploads are insert-only. |
| Duplicates in a file | The first occurrence wins; later copies are `DUPLICATE_IN_FILE` ("Duplicate of row 54"). |
| Validation | All errors in a row are reported. Whitespace is trimmed; dates must be strict `DD-MM-YYYY`; mobile must be 10 digits; gender `F`/`M`; email optional. |
| Large files | `POST` returns `202` immediately. One background worker processes uploads FIFO in 1,000-row chunks (one transaction each) and updates progress counters. The UI polls every second. |
| Safety | MySQL unique indexes back the application's dedup. Interrupted uploads are marked `FAILED` on restart; re-upload is safe. |
| Error report | Streamed CSV with the original raw values; spreadsheet formulas are neutralised. |

## Tests

```bash
cd backend
./mvnw test          # 42 tests: unit + integration (needs JDK 21+ and Docker running)
./mvnw test -Pperf   # 50,000-row performance test (~9 s for 50k rows; target < 60 s)
```

Integration tests start a real MySQL with Testcontainers. The main one, `AcceptanceScenarioTest`, replays the walkthrough above against the real data files and asserts the exact counts and rejected row numbers.

| Test | Covers |
|---|---|
| `RowValidatorTest` | One case per data problem in the provided files, plus the field rules |
| `DeduplicatorTest` | Insert / skip / duplicate-in-file / conflict, across chunks, case-insensitivity |
| `ErrorReportWriterTest` | Spreadsheet-formula values are neutralised; ordinary values untouched |
| `AcceptanceScenarioTest` | Preload → upload-1 → corrected → corrected again, on real MySQL |
| `ApiIntegrationTest` | Async upload via HTTP, report contents, file-level rejections, reordered columns, blank lines, paging |
| `PerformanceTest` | 50k rows < 60 s, then idempotent 50k re-upload (`-Pperf` only) |

## API

| Method | Path | |
|---|---|---|
| `POST` | `/api/uploads` (multipart `file`) | `202` + upload. `400` for file-level problems (not CSV, bad header, empty, not UTF-8) |
| `GET` | `/api/uploads/{id}` | Status, `totalRows`, `processedRows`, inserted / skipped / rejected |
| `GET` | `/api/uploads?limit=20` | Upload history |
| `GET` | `/api/uploads/{id}/errors.csv` | Error report |
| `GET` | `/api/employees?page=0&size=50&q=&department=` | Paged employee list |
| `GET` | `/api/employees/departments` | Department filter values |

## Project layout

```
docker-compose.yml
data/                     provided files, unchanged
backend/                  Spring Boot app
  src/main/java/com/example/employees/
    importer/             CSV reading, validation, dedup, batch writes (core logic)
    upload/               upload API, worker queue, error report, startup preload
    employee/             employee list API
    config/               executor, clock, app properties
    web/                  consistent JSON error responses
  src/main/resources/db/migration/V1__init.sql
  src/test/java/...       unit, integration (Testcontainers) and performance tests
frontend/                 index.html, app.js, styles.css + nginx config (proxies /api)
```

## Time spent

**About 3 hours in total**, within the 3-hour box.

- First attempt, discarded: data profiling, an early build, local-run problems. I restarted spec-first ([`ai-log.md` section 2.0](ai-log.md#20-first-attempt-discarded-transcript-sessions-13)).
- `spec.md` and `plan.md`: profiling-driven rules, simulated expected outcomes, spec review
- Build M1–M6, one commit per milestone, each run before the next
- Docker build fixes on my network
- Doc review, fresh-clone verification against the brief's checklist, comment cleanup

## What I'd do with another day

See [`plan.md`](plan.md#with-another-day): fuzzy duplicate detection, an approve-the-diff update flow for conflicts, non-blocking warnings (e.g. under-18 at joining), auth + audit trail, resumable processing.
