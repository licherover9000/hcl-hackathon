# CLAUDE.md

Instructions for Claude Code in this repository. They apply to every session, every task and every teammate. This is a live HCLTech campus hackathon used for placements: every member is scored individually on whether the demo runs end to end and whether they can explain and defend any part of the code. A broken feature, or code a teammate cannot explain, costs real offers. Accuracy beats speed.

## 0. Non-negotiable rules

1. **No git writes. Ever.** Humans commit and push; you never run `git add`, `commit`, `push`, `merge`, `rebase`, `reset`, `checkout`, `restore`, `switch`, `stash`, `clean` or `branch -D`. Read-only git (`status`, `diff`, `log`, `show`) is fine. If a task seems to need a git write, tell the human the exact command and let them run it.
2. **Never break working code.** Anything marked ✅ in the README user story table is locked. Run the full test suite before you start (baseline) and after you finish. If your change must touch a locked feature, say so in your plan and wait for approval.
3. **No destructive actions without explicit approval in the same message:** deleting files or folders, overwriting a file you have not read, dropping or resetting a database, editing `.env`, removing or upgrading dependencies.
4. **Never claim it works without running it.** Show the real command and its real output. If you could not run something, say exactly that. Never invent output.
5. **Smallest correct change.** Touch only what the task needs. No drive-by refactors, renames, reformatting or "improvements" outside the task.
6. **Never weaken a test to make it pass.** Fix the code, or explain why the test itself is wrong and wait for approval.
7. **Ask instead of guessing** when a requirement, test case or file location is unclear. One short question beats a wrong feature.
8. **Explainable code only.** Simple and readable beats clever. Any teammate must be able to explain any file to a panel within a minute.
9. **No secrets in code.** Use `.env` (gitignored) and keep `.env.example` current.
10. **Write assumptions down.** When the case study is ambiguous or its rules overlap, pick one reading, apply it consistently, and record it in the README "Assumptions" section and the step report. Panels treat a written assumption as a design decision and an unwritten one as a bug.

## 1. Project

- **Repo:** https://github.com/licherover9000/hcl-hackathon
- **Problem (one line):** RecoverPlus (case_03): day-end collections engine, worklists, fair-practice contact rules, settlement maker-checker, audit, AI miss-next-instalment model.
- **Users:** Collector, Collections Manager, Head of Collections, Auditor.
- **Tech stack:** React 18 + Vite (frontend), Java 21 + Spring Boot 3 + JdbcTemplate + Flyway (backend), PostgreSQL 16, Python + scikit-learn + FastAPI (ml).
- **Folder layout:** `backend/src/main/java/com/threepointers/recoverplus/` (`engine` pure calculation and rules, `io` CSV/JSON and outputs, `data` DB repository and imports, `service`, `web`, `security`, `batch`); `backend/src/main/resources/db/migration`; `frontend/src/pages`; `ml/`; `scripts/`; `data/input/` (data pack); `output/`; `docs/steps/`.
- **Run app:** `docker compose up -d db`; `cd backend && mvn spring-boot:run`; `cd frontend && npm install && npm run dev`; optional `uvicorn serve:app --app-dir ml --port 8001`.
- **Run tests:** `cd backend && mvn test` (worked examples); `scripts/run_evaluation.ps1 -AsOf 2026-03-31` compares with the expected file.
- **Evaluation:** `scripts/run_evaluation.ps1 -AsOf 2026-04-15 -Eval` writes `output/collections_output.csv` and `output/settlement_decisions.csv`.
- **Never change** the engine without rerunning WorkedExamplesTest and the expected-file comparison.

## 2. Team and ownership

Each member commits their own work from their own GitHub account, so contributions are visible in history. Two people never edit the same file in parallel.

| Member | Owns (folders / modules) |
|---|---|
| Pranjal | TBD |
| Member 2 | TBD |
| Member 3 | TBD |

**Shared files** (dependency manifest, app entry point, router registry, DB schema) have exactly one owner listed above. If your task needs a change there, keep it minimal and flag it in the handoff so the owner knows.

## 3. User stories

Canonical list with priority. Live status lives in the README table (✅ working and tested, 🚧 in progress, ⬜ not started).

| ID | User story | Priority | Owner | Test file |
|---|---|---|---|---|
| S1 | TBD | Must | | |

Build order: all Must stories end to end first, then Should, then Could. A thin working slice beats a wide broken one. Evaluators may check progress every 30 to 40 minutes, so keep tasks small enough to finish and commit within about 30 minutes, and keep the README status table current.

Every story needs input validation, business rule validation, unit tests and consistent error responses across API and UI; past HCLTech juries separated teams on exactly these.

## 4. Session start

1. Read this file, `README.md` and the newest file in `docs/steps/`. Do not scan the whole repo unless the task needs it.
2. Run `git status` and `git log --oneline -5`. Teammates, merges or panelists may have changed files. **The files on disk are the truth, not your memory.** Re-read any file before editing it.
3. Confirm who is driving and which story or task ID. If not stated, ask.

## 5. Task workflow (every task)

1. **Plan.** For anything beyond a one-line fix: files to touch, approach, one trade-off considered, tests to add. Wait for "go".
2. **Baseline.** Run the test suite; note any failures that already existed.
3. **Implement.** Minimal change in the correct files, following the existing structure and style.
4. **Verify.** Run the new tests, the full suite and one real smoke check (actual request, command or UI action). Fix until green.
5. **Document.** Write the step report (section 7). Update `README.md` if setup, API, behavior or story status changed.
6. **Hand off.** End with the handoff block (section 8).

**Done means:** works, tested, nothing previously working is broken, report written, README current.

## 6. Code standards

- One obvious home per concern (routes, business logic, data access, UI). No logic hidden in unexpected places.
- Small functions with clear names. Comments explain *why*, not *what*.
- Validate every input at the boundary. Return clear errors with correct status codes or messages; never crash on bad input. Always consider empty, missing, duplicate, invalid type and boundary values.
- Every user story has automated tests that mirror the given test cases, plus its edge cases.
- Errors fail loudly with a readable message, so anyone can tell what broke and where.
- No new dependency unless you justify it in the plan; pin versions; prefer the provided stack.
- No dead code, commented out blocks or leftover debug prints.
- Demo and seed data live in one place so the demo is reproducible from a clean start.

## 7. Step report

After every task, write `docs/steps/NN-<storyID>-<short-name>.md` (NN = next number, e.g. `03-S2-add-booking.md`). Factual and tight, roughly 40 to 80 lines:

1. **Summary:** what was built and why (2 to 3 lines)
2. **Files changed:** each file, new or modified, one line on its role
3. **How it works:** the flow from input to output, naming files and functions in order
4. **Design decisions:** what was chosen, the alternative, the trade-off
5. **Edge cases handled**, and known limitations
6. **Run and test:** exact commands and expected output
7. **Change scenarios:** 3 concrete "if someone changes X, then Y happens" cases a panelist might try, each with where to look to fix it
8. **Driver:** the teammate who ran this task

The report must match the code exactly. Never describe something that was not implemented.

## 8. Handoff block (end of every task, in chat)

```
DONE: <task ID>, <one line>
Tests: <passed / failed> via <command>
Files: <list>
Shared files touched: <none / list>
Report: docs/steps/<file>.md
Safe to commit: <yes / no, and why>
Suggested commit: <type>(<scope>): <message>
Explain back: <3 bullets the driver must be able to say out loud>
```

## 9. When something breaks

- A test that used to pass now fails: stop new work, fix the regression first, report the root cause.
- Two failed fix attempts: stop, explain what you tried, and suggest the human restore the last commit for the affected files.
- Unsure whether something is safe: ask.

## 10. Modes

- A message starting with `ASK:` is read only. Explain, trace or answer; do not edit any file.
- A message starting with `REVIEW:` means review the current `git diff` for bugs, regressions and rule violations; do not edit.

## 11. Usage limits

- Keep chat replies short. Detail goes in the step report, not the chat.
- Never print whole files in chat; reference paths and line numbers.
- Do not re-read files already read this session unless they may have changed.
- One task per session where possible; humans will clear the session between tasks.
