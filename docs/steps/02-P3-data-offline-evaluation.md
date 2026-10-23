# Step 02 (P1 + P3): Data loading and the offline evaluation run

**Owner:** Data handling & management · **Status:** done, tested on a sample pack

## Summary
Reads the case_03 data pack from `data/input/`, runs the day-end engine for a date and writes `output/collections_output.csv` and `output/settlement_decisions.csv` in the exact column order of section 6.3. Uses only the JDK (own CSV and JSON readers), so the evaluation run works even without the database or Spring. Includes a compare script against the expected sample file.

## Files
| File | Role |
|---|---|
| `backend/.../io/CsvIo.java` | RFC 4180 CSV reader/writer (quotes, commas, newlines in fields) |
| `backend/.../io/JsonFlattener.java` | Reads the rules config JSON into dotted keys for the config table |
| `backend/.../io/Book.java` | In-memory loan book; payments and contacts keyed by id, so re-imports add nothing |
| `backend/.../io/BookLoader.java` | Finds files by name, loads seed files, then evaluation files on top |
| `backend/.../io/OutputWriter.java` | Writes collections_output.csv, settlement_decisions.csv, risk_scores.csv |
| `scripts/run_evaluation.ps1` / `.sh` | Compile with javac and run; for the sample date also runs the comparison |
| `scripts/compare_expected.py` | Column-by-column comparison, prints mismatch counts and examples |
| `scripts/make_sample_pack.py` | Builds a 10-loan test pack from the worked examples |
| `data/README.md` | Where to put the data pack |

## How it works
1. `BookLoader.loadSeed()` reads customers, loans, schedule, payments, contacts, users, rules JSON and sample settlement requests. Dates accept `YYYY-MM-DD`, `DD-MM-YYYY`, `DD/MM/YYYY`; timestamps accept ISO-8601 with offset (IST assumed if none).
2. With `--eval`, `loadEvaluation()` adds `payments_eval` and `contact_history_eval` (duplicate ids skipped and counted) and replaces settlement requests with `settlement_requests_eval`.
3. `DayEndProcessor` computes every loan; `OutputWriter` writes the files sorted by loan_id.

## Edge cases handled
- Duplicate payment_id or contact_id: ignored, and the log prints how many were skipped.
- Missing rules key: case default used and listed on screen.
- Bad date: error names the file line.

## Run and test
```
# Windows, from the repo root
powershell -ExecutionPolicy Bypass -File scripts\run_evaluation.ps1 -AsOf 2026-03-31
powershell -ExecutionPolicy Bypass -File scripts\run_evaluation.ps1 -AsOf 2026-04-15 -Eval
# macOS / Linux / Git Bash
bash scripts/run_evaluation.sh 2026-03-31
bash scripts/run_evaluation.sh 2026-04-15 --eval
```
Tested here on the sample pack: 10 loans in ~0.2 s, comparison 10 / 10 rows matching on every column.

## Change scenarios a panelist may try
1. Import the payment eval file twice: the second time adds 0 rows because payments are keyed by payment_id.
2. Rename a column in the CSV header: the loader returns null for it and the run fails with the line number; header names are matched case-insensitively.
3. Run the same date twice: identical files, because everything is recomputed from source data.

**Driver:** [Data owner name]
