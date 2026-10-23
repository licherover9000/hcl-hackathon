# Step 04 (P4): Spring Boot API, security, contacts, settlements, day-end service

**Owner:** Backend · **Status:** compiles with zero errors against exact stand-ins of the Spring, Jackson and servlet APIs it uses (Maven Central was blocked in the build environment); every main SQL statement run on PostgreSQL 16 with the real schema. Not yet run end to end with Spring: do that on a laptop (`mvn spring-boot:run`).

## Summary
REST API over PostgreSQL that uses the same engine as the offline run. Adds login with four roles, server-side role and object checks, contact logging with fair-practice rules (422), settlement maker-checker (403 self-approval, 409 race), the day-end service that stores snapshots and writes the outputs, config editing, dashboard and audit endpoints, and a command-line batch mode.

## Files
| File | Role |
|---|---|
| `resources/application.yml` | DB, data/output folders, auth secret, ML URL and timeout, CORS |
| `security/AuthService` | Login (SHA-256 password hash) and HMAC-signed tokens with expiry |
| `security/AuthInterceptor`, `WebConfig` | Token check on every `/api/**` except login/health; CORS to the frontend only |
| `security/CurrentUser` | Logged-in user; role normalised to COLLECTOR / MANAGER / HEAD / AUDITOR; `require(...)` throws 403 |
| `web/ApiControllers` | All endpoints (thin: HTTP to service) |
| `web/ApiException`, `ApiExceptionHandler` | One error format; internal errors return a generic message |
| `service/DayEndService` | Load book, ML scores (fallback), engine, replace the date's positions in one transaction, PTP statuses, outbox and letters, PAID/LAPSED, audit, output CSVs |
| `service/LoanService` | Worklist (rules or AI order), queue summary, account 360 with FIFO view, object-level access check, plain-language explanation |
| `service/ContactService` | Fair-practice validation (422) and logging; blocked attempts are audited |
| `service/SettlementService` | Propose (evaluated on the position at request date), decide (403/409), list, evaluate the request file |
| `service/ConfigService` | Read rules config; head-only edit with reason, audited before/after |
| `service/DashboardService` | Buckets, roll-rate matrix between two dates, PTP kept rate, blocked contacts, settlements |
| `service/AuditService` | Insert-only audit writer and search |
| `service/MlClient` | Calls the Python service with a timeout; null on any failure |
| `service/Templates` | Polite reminder, pre-legal notice and settlement letter texts |
| `batch/BatchRunner` | `--job=seed` / `--job=evaluate --as-of=... [--eval]` then exit |

## Key flows
- **Login:** `POST /api/auth/login` → hash check → token `base64(userId:expiry).HMAC`. Any change to the token breaks the signature (401).
- **403 on someone else's loan:** `LoanService.checkAccess`: a collector must be the assignee or in the same queue and city.
- **422 contact:** `ContactService.log` checks hold, 08:00 to 19:00 IST, calls/visits per loan per day, PTP date within 15 days, PTP amount within total dues; blocked attempts are written to the audit log, then 422 with the reason.
- **403 / 409 settlement:** `SettlementService.decide`: proposer = current user → 403; manager on a PENDING_HEAD request → 403; `UPDATE ... WHERE status = ? AND version = ?` affecting 0 rows → 409.
- **Day-end:** one `@Transactional` method; re-running a date deletes and rewrites that date's rows, so results are identical.

## Design decisions
- **JdbcTemplate, not JPA:** explicit SQL is easy to read and explain, bulk inserts are fast; trade-off: more SQL by hand.
- **Optimistic locking (version column):** no locks held while a user reads; the loser of a race gets 409.
- **Stateless HMAC token (no library):** small and explainable; trade-off: no server-side logout list.
- **Interceptor + service checks:** hiding menus is not security; every call is checked on the server.

## Run and test
```
cd backend
mvn -q compile                     # first: confirm it compiles
mvn test                           # worked examples
mvn spring-boot:run                # API on :8080, Swagger /swagger-ui.html
```
Manual checks for the demo: log a call at 20:30 → 422; 4th call same day → 422; proposer approves own settlement → 403; approve the same request in two tabs → second gets 409; edit a config value as head → audit shows before/after.

## Change scenarios
1. Remove the `version = ?` condition in `decide`: two approvers can both succeed; the 409 demo breaks.
2. Change `maxCallsPerDay` in rules config to 2: the 3rd call of the day now returns 422; no code change.
3. Remove `AuthInterceptor` from `WebConfig`: every API call fails with 401 "Not logged in" because services read the user from the request.

**Driver:** [Backend owner name]
