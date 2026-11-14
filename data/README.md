# Data files

All three CSVs share the same header:

| Column | Type | Notes |
|---|---|---|
| `first_name` | text | mandatory |
| `last_name` | text | mandatory |
| `dob` | date `DD-MM-YYYY` | mandatory |
| `gender` | `F` / `M` | mandatory |
| `mobile` | 10 digits | mandatory |
| `email` | text | **optional** (may be empty) |
| `department` | text | mandatory |
| `designation` | text | mandatory |
| `date_of_joining` | date `DD-MM-YYYY` | mandatory |

- **`preloaded-employees.csv`** — 50 employees already in the system. Your app loads these at startup. This file is clean.
- **`upload-1.csv`** — 500 rows as an HR admin would upload them. Contains realistic data-quality problems; handling them well is part of the task.
- **`upload-2-corrected.csv`** — the same upload after the admin fixed the problems your error report would have flagged. Used to test idempotent re-upload.
