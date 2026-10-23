# RecoverPlus: Team 3Pointers, HCLTech Campus Hackathon (Use Case 03)

Loan collections early warning, delinquency rules and recovery prioritisation with settlement maker-checker, for Narmada Finance Bank (fictional, ~2,310 loans).

## Problem
Collectors work from a daily spreadsheet, call whoever they choose, calculate late fees by hand and get settlements approved over email. About 28% of SMA-0 accounts roll into SMA-1 every month, fair-practice breaches cause complaints, and nobody sees defaults coming.

## Our approach
A day-end engine turns the loan book into an auditable action plan: FIFO payment allocation, days past due, SMA/NPA bucket, overdue, penal charges and total dues; collection rules decide strategy and queue; ranked worklists per collector; fair-practice checks on every contact; settlements routed by discount with maker-checker; an append-only audit trail; and an AI model that warns before a miss and ranks worklists by expected recovery. Rules stay authoritative; AI only orders and suggests reminders.

## Architecture
```
React (Vite)  --REST + bearer token-->  Spring Boot 3 / Java 21  --JDBC-->  PostgreSQL 16 (Flyway, append-only audit trigger)
                                              |  pure-Java engine (also runs offline, no DB)
                                              +--HTTP, 2 s timeout, fallback--> Python FastAPI scoring (scikit-learn)
```

| Folder | What | Owner |
|---|---|---|
| `backend/.../engine` | Calculation engine and rules (pure Java, unit-tested) | Backend |
| `backend/.../service`, `web`, `security`, `batch` | API, auth, contacts, settlements, day-end, dashboard, audit | Backend |
| `backend/.../io`, `data`, `db/migration`, `scripts`, `docker-compose.yml` | CSV/JSON loading, DB schema, imports, outputs, evaluation run | Data handling & management |
| `frontend/` | React screens | Frontend |
| `ml/` | Model training, evaluation, scoring service | AI/ML |
| `docs/steps/` | One report per build step | everyone |

## User stories
| ID | Story | Priority | Status |
|---|---|---|---|
| S1 | Day-end computes position (DPD, bucket, overdue, penal, total dues) exactly as the case | Must | ✅ (worked examples 1 to 14 pass) |
| S2 | Rules R1 to R12 set strategy, queue and codes; config-driven | Must | ✅ |
| S3 | Output CSVs for an as-of date, compared against the expected file | Must | ✅ (sample pack 10/10); real file: run the script |
| S4 | Ranked worklists per queue, assigned within capacity | Must | ✅ |
| S5 | Login with 4 roles, server-side role and object checks (403) | Must | 🚧 written, compile on a laptop with Maven |
| S6 | Contact logging with fair-practice checks (422) | Must | 🚧 |
| S7 | Settlement propose / approve / reject, 403 self-approval, 409 race, PAID / LAPSED | Must | 🚧 |
| S8 | Append-only audit trail with CSV export | Must | 🚧 (DB trigger tested ✅) |
| S9 | Dashboard: buckets, roll-rate matrix, PTP kept rate, blocked contacts, settlements | Should | 🚧 |
| S10 | AI: miss-next-instalment model, report, model card, risk_scores.csv, R13, AI worklist | Should (AI track) | ✅ pipeline; rerun on real data |

Legend: ✅ working and tested · 🚧 in progress · ⬜ not started

## Run it

**Evaluation run (no database needed):** put the data pack in `data/input/`, then from the repo root
```
powershell -ExecutionPolicy Bypass -File scripts\run_evaluation.ps1 -AsOf 2026-03-31            # check vs expected
powershell -ExecutionPolicy Bypass -File scripts\run_evaluation.ps1 -AsOf 2026-04-15 -Eval      # evaluation data
```
Outputs: `output/collections_output.csv`, `output/settlement_decisions.csv` (and `output/risk_scores.csv` if `output/ai_scores.csv` exists).

**Full app:**
1. Database: `docker compose up -d db` (or a local PostgreSQL with db/user/password `recoverplus`).
2. ML (optional): `pip install -r ml/requirements.txt`, `python ml/train.py --data data/input --out output`, `uvicorn serve:app --app-dir ml --port 8001`.
3. Backend: `cd backend && mvn spring-boot:run` (Swagger at http://localhost:8080/swagger-ui.html).
   Or a one-shot batch: `mvn -q package -DskipTests && java -jar target/recoverplus-1.0.0.jar --job=evaluate --as-of=2026-03-31`
4. Frontend: `cd frontend && npm install && npm run dev`, open http://localhost:5173.
5. Log in as a manager or head (user IDs from the users file, password `demo123`), open **Day-end and data**: Load seed data, then Run day-end.

## API (main endpoints)
`POST /api/auth/login` · `GET /api/worklist?mode=rules|ai` · `GET /api/loans/{id}` · `POST /api/loans/{id}/contacts` ·
`POST /api/loans/{id}/reassign` · `GET/POST /api/settlements` · `POST /api/settlements/{id}/decision` ·
`POST /api/admin/seed` · `POST /api/admin/import/payments` · `POST /api/admin/import/evaluation` · `POST /api/admin/day-end` ·
`POST /api/admin/settlement-file` · `GET /api/admin/runs` · `GET /api/admin/outbox` · `GET /api/admin/outputs/{file}` ·
`GET/PUT /api/config` · `GET /api/dashboard` · `GET /api/audit` · `GET /api/audit/export`. Full contract: Swagger UI.
Errors: `{"status","code","message","timestamp"}`; 401 not logged in, 403 not allowed, 404 not found, 409 already decided, 422 business rule (with the reason).

## Assumptions
- A1 A hold (dispute/deceased) also applies to a CURRENT loan: it sits on the hold desk with a rank.
- A2 R9 escalation applies only when the loan has a ladder strategy (not NONE or HOLD).
- A3 Broken PTPs are counted when ptp_date is within the 60 days before the as-of date (as-of − 60 included).
- A4 R12 needs at least 3 contacts before the as-of date.
- A5 discount_pct is reported for every settlement request; the hold check comes before the bucket check (worked example S0006).
- A6 Settlements proposed in the app use the latest day-end date as the request date (the data is historical).
- A7 Collectors may open loans assigned to them or in their queue and city; managers, head and auditor see all; the head may also decide manager-level settlements.
- A8 A contact at exactly 19:00 IST is allowed.
These are checked against the expected sample file; any mismatch is fixed in one place in the engine.

## How we used AI
Claude (chat) helped analyse the case and design the system; Claude was used as a coding assistant for the code in this repo. Every calculation is verified by unit tests built from the case's worked examples and by comparing our output with the expected sample file; each step's report in `docs/steps/` explains the code so every team member can defend it. ChatGPT and Gemini were used for presentation and question practice.
