# AI Log

**Tool:** Claude Code (Claude Opus 5.5)
**Workflow:** spec-driven, after one discarded first attempt (section 2.0). I had the AI help me write [`spec.md`](spec.md) and [`plan.md`](plan.md) before any code, and every implementation prompt after that pointed back to them. I set the scope, made the decisions, and reviewed each milestone. The AI did the profiling, drafting and implementation under that direction.

---

## 1. How I used the AI

| Phase | My input | AI output | My check before moving on |
|---|---|---|---|
| Data profiling | Asked for a profile of all three CSVs before any rules were written | Script covering formats, empty fields, column counts, whitespace, duplicates under several candidate keys, overlap with preload, and a diff of `upload-1` vs `upload-2-corrected` | Read the findings myself; they became spec section 2 |
| Spec | "Plan first, then write the spec. Spring Boot + MySQL." | Draft `spec.md`: problem restatement, data findings, decisions D1–D20, identity rule (section 4) | Reviewed and changed it (see section 2 below) |
| Plan | Milestones M1–M6 with a time box | `plan.md`: milestones, "done when" criteria, data model, API, test strategy | Checked that every "done when" was something I could actually run |
| Build | "Implement according to spec and plan, one milestone at a time" | M1–M6 in order | Ran each milestone (compose, tests or browser) before the next one started |

One rule I kept to throughout: no business rule went into code until it had been run against the real files, and those numbers became the acceptance test.

---

## 2. Session log

### 2.0 First attempt (discarded)
- I opened with "we need to complete this task". The AI profiled the data and started writing code, in Python, before agreeing on a plan. I stopped it and set the stack: plan first, Spring Boot + MySQL.
- During that build I asked it to switch to JPA. When I ran the app from IntelliJ it failed to start, because MySQL was only reachable inside Docker.
- Partway through I stopped and restarted in a new session, spec first. Everything below, and all the code in the repo, comes from that restart.

### 2.1 Profiling → identity rule (spec sections 2 and 4)
- The profile showed that `upload-2-corrected.csv` still has 3 near-duplicate pairs: same person, mobile differs by one digit.
- A mobile-keyed identity would have loaded those as 6 employees. That's why I went with the three-key identity rule (name+DOB, mobile, email) in spec section 4.
- Before writing code, I had the AI simulate the proposed rules against the real files. It produced the expected outcomes in spec section 2.3: **480/5/15 → 8/485/3 → 538 total**. These went straight into `AcceptanceScenarioTest`.

### 2.2 Spec review
Prompt: *"Is this spec the best way to complete the task?"* Changes made after that review:
- Added the "Key decisions at a glance" section at the top so the main choices are easy to find.
- Removed a "joining date ≤ 1 year in the future" rule. Nothing in the data justified it, and it made tests depend on today's date. It was replaced by D7 (future DOJ allowed, injectable `Clock`).
- Moved upload history and search to stretch goals (spec section 5.4) so the must-haves fit the 3-hour box.

### 2.3 Implementation (plan M1–M6)
| Milestone | Notes |
|---|---|
| M1 | Compose + Flyway schema + preload. Preload moved into M2 because it reuses the import pipeline (D19). |
| M2 | Before the first run, the AI noticed its own schema used `row_number` as a column name. That is a reserved word in MySQL 8, so the column was renamed. The acceptance test matched every count first time but failed on one error message (section 3, item 1). |
| M3 | APIs checked with a curl walkthrough against the spec section 5.2 report examples. |
| M4 | UI checked by driving headless Chrome over the DevTools protocol: choose a real file, click Upload, take screenshots. A 50k-row upload went QUEUED → 0% → 46% → 86% → COMPLETED. |
| M5 | 13 failures in the new unit tests; every one was a test bug, not a code bug (section 3, item 3). 50k-row perf test: **9.2 s** against a 60 s target. |
| M6 | README and this log written; a clean-checkout `docker compose up` run against the brief's checklist. |

---

## 3. Where the AI was wrong and how I caught it

The biggest mistake was in the spec the AI drafted. It said row 108 of the corrected file would "match employee #60", but by its own "first occurrence wins" rule that row matches row 11 of the same file. The acceptance test caught it. When I asked whether the spec was really the best approach, it also dropped a joining-date rule that nothing in the data supported. Later it wrote 13 broken unit tests; running the suite showed all of them, and none needed a code change. Most of these were caught because the expected numbers came from the real files and were turned into tests early.

Details:

| # | Issue | How it was caught | Resolution |
|---|---|---|---|
| 1 | Spec contradicted itself. The example message said corrected row 108 would "match employee #60". But under the spec's own D12 ("first occurrence in the file wins"), row 108 is matched against row 11 of the same file first. | `AcceptanceScenarioTest` assertion on the error message | Fixed the spec and the code. The message now points at the row in the file the admin is editing and says that row is already in the system (spec section 5.2). It's a clearer message for the admin too. |
| 2 | Rule nobody asked for. "DOJ ≤ 1 year in the future" had no basis in the data and made tests time-dependent. | My spec review | Removed; replaced with D7 + injectable `Clock`. |
| 3 | Broken tests. (a) a helper passed `null` to `List.of`; (b) an assertion expected one error where the validator correctly reported two (D18 says report all errors); (c) a `@CsvSource` used `'` as its quote character *and* inside expected values. | Running the suite | Fixed the tests only. No production code needed to change, which I confirmed by checking each failure against the spec. |
| 4 | Reserved word. The `row_number` column name in the schema. | The AI flagged it during M2 self-review | Column renamed before the first run. |

---

## 4. Takeaways

- Writing the spec first helped. Most of the AI's mistakes showed up as a mismatch with `spec.md` or the section 2.3 numbers, before they could become real bugs.
- Looking at the data before writing rules mattered. If I'd written the rules from the brief alone, the near-duplicates would have loaded as duplicate employees.
- AI-written tests need reviewing like any other code. All 13 failures in M5 were bugs in the tests themselves, so a green run only counts once the tests have been checked against the spec.
- Next time I'd ask for the error-message examples in spec section 5.2 to be generated by the simulation script, not written by hand. That would have caught issue #1 at spec time instead of test time.
