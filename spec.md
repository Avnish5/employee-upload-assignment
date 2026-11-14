# Spec: Bulk Employee Upload

**Status:** final · **Stack:** Spring Boot 3.5 (Java 21) + MySQL 8.4 + plain HTML/JS (nginx) · **Run:** `docker compose up` · **Build plan:** [`plan.md`](plan.md)

**Contents:** [Key decisions](#key-decisions-at-a-glance) · [1. Problem](#1-problem-restated) · [2. Data](#2-what-the-data-actually-contains) · [3. Decisions](#3-ambiguities-and-decisions) · [4. Identity](#4-employee-identity-d9) · [5. Functional](#5-functional-requirements) · [6. Non-functional](#6-non-functional-requirements) · [7. Out of scope](#7-out-of-scope)

## Key decisions at a glance

1. **Identity uses three keys.** A row is "the same employee" if it matches on name + DOB, on mobile, or on email. All three keys are unique in the database (section 4).
2. **Idempotency.** A row identical to an existing employee is **skipped**, not reported. A row that matches only partly is **rejected as a conflict**. Uploads never update existing records.
3. **Partial load.** Valid rows load and invalid rows go to a CSV error report numbered by spreadsheet row (header = row 1).
4. **Strict values.** Dates must be `DD-MM-YYYY` and are never guessed. Leading/trailing whitespace is trimmed.
5. **Async processing.** The upload returns immediately. A single background worker processes the file in 1,000-row chunks, and the UI polls progress every second.

> Note: the corrected file still gives 3 errors. `upload-2-corrected.csv` still contains 3 near-duplicate pairs: same person, mobile differs by one digit. Rejecting them is deliberate (sections 2.2 and 4). A mobile-keyed rule would silently create 3 duplicate employees.

---

## 1. Problem restated

HR admins onboard employees in bulk by uploading a CSV (500 – 50,000 rows) that comes from the field and has data-quality problems. The system must:

1. Upload a CSV and show live progress (rows processed / total, counts by outcome).
2. Produce a downloadable error report with one line per rejected row, using the row number from the input file so the admin can fix the source.
3. Make re-upload idempotent: uploading the whole file again after fixing some rows loads the fixed rows and doesn't duplicate the ones already loaded.
4. Show loaded employees in a list view.
5. Preload `data/preloaded-employees.csv` at startup. Duplicates are checked within a file and against employees already in the system.

The upload has no employee-ID column, so the system has to decide what makes two rows "the same employee". See section 4.

---

## 2. What the data actually contains

I profiled all three files with a script before writing any rules. All files are UTF-8, CRLF line endings, no BOM, and share the same 9-column header.

### 2.1 `upload-1.csv` (500 data rows, file rows 2–501)

| File row | Problem | Our outcome |
|---|---|---|
| 101 | `mobile` empty | Rejected: required field |
| 116 | `dob` = `17/06/1992` (slashes) | Rejected: date format |
| 215 | `dob` = `31-02-1990` (impossible date) | Rejected: invalid date |
| 295 | `first_name` empty | Rejected: required field |
| 353 | `date_of_joining` = `17-Jun-2023` | Rejected: date format |
| 369 | `dob` empty | Rejected: required field |
| 426 | 8 columns (missing `department`) | Rejected: column count |
| 499 | `date_of_joining` = `45-13-2023` | Rejected: invalid date |
| 181 | mobile `' 7388606964 '` (padded) | **Accepted** after trimming |
| 451 | `'  Farida'`, `'Njoroge '` (padded) | **Accepted** after trimming |
| 208, 376, 430, 469 | Exact copies of rows 54, 72, 231, 460 | Rejected: duplicate within file |
| 109, 193, 429 | **Near-duplicates** of rows 11, 118, 256: same name, DOB, email, dept, designation and DOJ, but mobile differs by one digit (e.g. `6148179048` vs `6148179548`) | Rejected: identity conflict (section 4) |
| 52, 250, 383, 456, 475 | Identical to preloaded employees | Skipped: already exists (not an error) |
| 39, 267, 271, 325, 329 | `date_of_joining` in the future (Nov–Dec 2026) | **Accepted** (section 3, D7) |
| 65, 85, 93, 142, 181, 220, 319, 485 | Age at joining < 18 (as low as 14.5) | **Accepted** (section 3, D8) |

### 2.2 `upload-2-corrected.csv` (496 data rows)

Diffing it against `upload-1.csv` shows the admin:
- fixed the 8 validation errors in place (rows 101, 116, 215, 295, 353, 369, 426, 499 of upload-1),
- deleted the 4 exact duplicates, so row numbers after each deletion shift up by one,
- trimmed the whitespace rows,
- left the 3 near-duplicate pairs in place.

This file is the real test of the identity rule. A rule keyed on mobile would load both rows of each near-duplicate pair, which creates 3 duplicate people. Our rule rejects the second row of each pair on every upload until the admin resolves it.

Data-quality issue we don't detect (noted, out of scope): corrected row 294 fills the blank first name as `Rohit`, but the row's email is `paulo.otieno345@…`. Detecting name/email inconsistency is fuzzy matching (section 7).

### 2.3 Expected outcomes (these become acceptance tests)

| Step | Inserted | Skipped (already exists) | Rejected | Employees in DB |
|---|---|---|---|---|
| Startup preload | 50 | 0 | 0 | 50 |
| Upload `upload-1.csv` | 480 | 5 | 15 | 530 |
| Upload `upload-2-corrected.csv` | 8 | 485 | 3 (rows 108, 192, 427) | 538 |
| Upload `upload-2-corrected.csv` again | 0 | 493 | 3 | 538 |
| Restart the stack | — | — | — | 538 (preload does not duplicate) |

---

## 3. Ambiguities and decisions

| # | Ambiguity | Decision | Why |
|---|---|---|---|
| D1 | What is a "row number"? | **Spreadsheet row number**: the header is row 1 and the first data row is row 2. It is computed from the CSV *record* index, not the physical line. | This is the number the admin sees when the file is open in Excel. If a quoted field contains a newline, record-based numbering still matches Excel; physical line numbers would not. |
| D2 | Whitespace in values | **Trim** leading/trailing whitespace and collapse internal runs of whitespace to a single space. Then validate. | You can't see padding in Excel and there's nothing ambiguous about it, so rejecting it would just annoy admins. |
| D3 | Non-canonical dates (`17/06/1992`, `17-Jun-2023`) | **Reject** with a message that shows the expected format `DD-MM-YYYY`. Dates are parsed strictly, with no rollover (`31-02` is invalid). | `DD/MM` vs `MM/DD` is ambiguous in general. Guessing could silently store a wrong date of birth, which is worse than asking the admin to fix it. The brief names the canonical format. |
| D4 | Header handling | The header row is required. Columns are matched **by name** (case-insensitive, trimmed), so **column order doesn't matter**. If any of the 9 expected columns is missing, the whole file is rejected before processing starts. Unknown extra columns are ignored. | Matching by name handles reordered exports. A missing column is a file-level problem, not a row-level one. |
| D5 | Wrong number of fields in a row | Reject the row (`COLUMN_COUNT`). Fields are never re-aligned. | Row 426 shows why: shifting fields would store a designation in the department column. |
| D6 | Field rules | `first_name`, `last_name`: required, ≤ 100 chars, letters plus space `' - .`. `gender`: `F`/`M`, case-insensitive, stored upper-case. `mobile`: exactly 10 digits after trimming. No `+91`/dash stripping. `email`: optional; if present must contain one `@` and a dot in the domain, ≤ 254 chars, stored lower-case. `department`, `designation`: required free text, ≤ 100 chars. No fixed list, because we don't have the authoritative one. | Covers every problem found in the data, and each rule is easy to explain. |
| D7 | Future `date_of_joining` | **Allowed**, with no upper limit. `dob` must be in the past and no earlier than `01-01-1900`, and `date_of_joining` must be after `dob` (all `DATE_RANGE`). "Today" comes from an injectable `Clock` so tests are deterministic. | This is an onboarding system, so people with a future joining date are normal. The corrected file keeps 5 future dates, so the admin didn't treat them as errors. |
| D8 | Minimum age at joining | **Not enforced.** | The preloaded file, which the brief calls clean, contains employees who joined at 17. The corrected file keeps 8 under-18 rows. Enforcing an age would contradict the reference data. A non-blocking warning is listed as future work. |
| D9 | Employee identity | See section 4. | — |
| D10 | Row exactly matches an existing employee | **Skipped** (`ALREADY_EXISTS`). Counted and shown in the summary, **not** in the error report. | This is what makes re-upload idempotent. An unchanged row is not an error. |
| D11 | Row matches an existing employee on identity but differs in other fields (e.g. new designation) | **Rejected** (`CONFLICT`). The report names the existing employee and the fields that differ. Uploads are **insert-only**. | Updating employees by bulk upload is a separate feature with its own risks (who approved the change?). Overwriting records without review is risky. |
| D12 | Duplicates within one file | The **first occurrence wins**. Later exact copies are rejected (`DUPLICATE_IN_FILE`, "duplicate of row N"). Later near-copies are rejected (`CONFLICT`, naming row N). | It's deterministic, and the report tells the admin exactly which row to delete. |
| D13 | All-or-nothing vs partial load | **Partial**: valid rows load and invalid rows are reported. | Combined with idempotent re-upload, the admin fixes only the bad rows and re-uploads the whole file. This is the workflow the brief describes. |
| D14 | Large files / progress | Processing is **asynchronous**. `POST` stores the file and returns `202` with an `uploadId`. A background worker processes it in chunks of 1,000 rows. Each chunk commits and updates progress counters. The UI polls every second. | A 50k-row file finishes in seconds to tens of seconds. Polling is simple and works through any proxy, so SSE/WebSockets aren't worth it here. |
| D15 | Concurrent uploads | Uploads run on a **single-worker queue** (one file at a time, FIFO; queued uploads show `QUEUED`). MySQL unique constraints are the final safety net. | Deduplicating across files running in parallel would need row locking or retries. One file at a time is fast enough and much easier to get right. |
| D16 | Crash mid-upload | On startup, uploads left `QUEUED` or `PROCESSING` are marked `FAILED` with a message telling the admin to re-upload. | Re-upload is idempotent, so restarting from scratch is safe. Rows already committed will simply be skipped. |
| D17 | File limits | ≤ 20 MB (about 4× a 50k-row file), `.csv` only, UTF-8, BOM stripped if present, CRLF or LF. An empty file, or a file with only a header, is rejected at file level. | Stops clearly wrong uploads (e.g. an `.xlsx`) before any rows are processed. |
| D18 | Error-report format | **CSV** (UTF-8 with BOM) containing the original raw row values plus `row_number`, `error_codes`, and `error_messages`. All errors for a row are listed, not just the first. A value starting with `= + - @` gets a leading `'` so Excel can't run it as a formula. | The admin can open it in Excel next to the source. Fixing everything in one pass avoids repeated fix/upload cycles. The report contains uploaded, untrusted text, so it must be safe to open. |
| D19 | Preload | Runs at startup through the **same import pipeline**, recorded as an upload with `source = PRELOAD`. | It reuses validation and dedup, and restarts don't duplicate anything (second start: 50 skipped). If the preload file is missing, the backend refuses to start rather than silently running empty. |
| D20 | Authentication | **None.** Single implicit HR-admin user. | Out of scope for a 3-hour exercise. Noted as the first production gap. |

---

## 4. Employee identity (D9)

### Rule

Every row is normalized (trimmed; names, email and gender case-folded for comparison) and checked against **three match keys**:

| Key | Definition | Enforced by |
|---|---|---|
| K1 (person) | `lower(first_name) + lower(last_name) + dob` | Unique index |
| K2 (mobile) | `mobile` | Unique index |
| K3 (email) | `lower(email)`, only when present | Unique index (MySQL allows multiple NULLs) |

Outcome of a valid row:
- No key matches any existing employee or earlier row in the file → **INSERT**.
- Keys match exactly one record and every field is equal after normalization → **SKIP** (`ALREADY_EXISTS`). If that record came from an earlier row of the *same* file, it's `DUPLICATE_IN_FILE` instead (reported).
- Anything else (a key matches but fields differ, or different keys point to different records) → **REJECT** (`CONFLICT`). The report names the matched record, which key matched, and which fields differ.

### Why this rule

- Mobile alone fails on the real data. The three near-duplicate pairs differ *only* in mobile, so they would load as six people.
- Name + DOB alone fails when mobile or email is re-used by a different name. That usually means a data-entry error, so it should be flagged.
- A row only counts as "same and unchanged" if all three keys agree. Any partial match shows up as a conflict rather than being guessed at.

### Known failure modes

| Failure | Example | Effect | Mitigation |
|---|---|---|---|
| False positive: two real people with the same name and DOB | Two "Amit Kumar, 01-01-1990" in a 50k-row file | Second one is rejected as a conflict | Visible in the report. Production would need an override or an official ID (e.g. national ID / service number). |
| False positive: shared mobile | Two family members share one phone | Second one rejected | Same as above. Could relax K2 to a warning if the business confirms shared numbers are legitimate. |
| False negative: typo in name and different mobile and email | "Sunil Josh" vs "Sunil Joshi", new phone, no email | Duplicate person created | Needs fuzzy matching (e.g. Jaro-Winkler on name + same DOB). Out of scope; listed as future work. |
| Legitimate update blocked | Employee promoted, re-uploaded with new designation | Rejected as conflict | Insert-only by design (D11). An update flow is future work. |
| Mobile-only change | Employee changed phone number | Rejected as conflict, because K1 matches and mobile differs | Same as above. The three near-dupes look exactly like this, so only a person can tell a new phone from a typo. |

---

## 5. Functional requirements

### 5.1 Upload
- The UI has a file picker and an Upload button. On submit, it switches to a status panel for that upload.
- The status panel shows the state (`QUEUED → PROCESSING → COMPLETED | FAILED`), a progress bar (`processed / total` rows), and live counters for inserted / skipped / rejected. It refreshes every 1 s until the upload reaches a terminal state.
- File-level failures (bad header, empty, too large, not CSV) show a message and no rows are processed.

### 5.2 Error report
- Available once the upload is `COMPLETED` and `rejected > 0`. A "Download error report" button returns `upload-{id}-errors.csv`.
- Columns: `row_number, error_codes, error_messages, first_name, last_name, dob, gender, mobile, email, department, designation, date_of_joining`. Values are the original raw values, untrimmed, so the admin sees exactly what was in the file. The only change is the formula-injection prefix (D18).
- Error codes:

  | Code | Meaning |
  |---|---|
  | `COLUMN_COUNT` | Row has the wrong number of fields (D5) |
  | `REQUIRED` | A mandatory field is empty |
  | `INVALID_FORMAT` | Value doesn't match the field rule (D6), or a date isn't `DD-MM-YYYY` (D3) |
  | `INVALID_DATE` | Correct format but impossible date, e.g. `31-02-1990` |
  | `DATE_RANGE` | DOB in the future or before 1900, or joining date not after DOB (D7) |
  | `DUPLICATE_IN_FILE` | Exact copy of an earlier row in the same file (D12) |
  | `CONFLICT` | Partial identity match with an earlier row or an existing employee (section 4) |
- Examples:
  - upload-1, row 109: `109,CONFLICT,"Matches row 11 of this file on name+dob, email; differs in: mobile (6148179048 vs 6148179548)",Fatima,Nair,…`
  - corrected file, row 108: `"Matches row 11 of this file (already in the system as employee #60) on name+dob, email; differs in: mobile (…)"`. Rows earlier in the same file are checked first, so the message points at the row the admin is editing.
  - A conflict with an employee who isn't in the file: `"Matches employee #12 (upload 2, row 40) on mobile; differs in: …"`

### 5.3 Employee list (must-have)
- A server-side paginated table (default 50 per page), sorted by last name then first name.
- Shows total count. Dates are displayed as `DD-MM-YYYY`.

### 5.4 Stretch goals (built after the must-haves were done and tested)
- **Upload history:** past uploads with state, counts, and a report link.
- **Search:** case-insensitive "contains" on name, mobile or email, plus a department filter.

---

## 6. Non-functional requirements

- One command: `docker compose up` builds and starts MySQL, the backend and the frontend, runs schema migrations, and loads the preload file. Open **http://localhost:8080**.
- Performance: 50,000 rows processed in < 60 s on a laptop. Use JDBC batch inserts, not one JPA `save` per row. Dedup lookups are batched per chunk using `IN (…)` queries on the indexed keys.
- Memory: the file is streamed and parsed row by row. Rejected rows go to an `upload_error` table, and the report is streamed out of it. The whole file is never held in memory.
- Failures: each chunk is one transaction. MySQL unique indexes stop duplicates even if the application logic has a bug.
- Tests: unit tests for parsing, normalization and validation. An integration test (Testcontainers MySQL) replays section 2.3 end to end against the real data files. The test command is documented in the README.

---

## 7. Out of scope

- Authentication, authorization, multi-tenant separation, audit of who uploaded.
- Updating or deleting employees, through upload or UI (insert-only, D11).
- Fuzzy / probabilistic duplicate matching (name typos, transliteration variants).
- Excel (`.xlsx`) uploads; non-UTF-8 encodings.
- Lenient date parsing or auto-correcting values beyond whitespace trimming.
- Non-blocking warnings (e.g. under-18 at joining, gender/name mismatch, name/email mismatch).
- Fixed reference lists for department and designation.
- Resuming a crashed upload mid-file (re-upload is idempotent instead).
- Horizontal scaling of the worker / distributed job queue.
- Polished UI, i18n, accessibility audit.
