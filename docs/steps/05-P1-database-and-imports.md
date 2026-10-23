# Step 05 (P1): Database schema, audit trigger, seed and imports, Docker

**Owner:** Data handling & management · **Status:** migrations tested on PostgreSQL 16; Java repository code reviewed, compile with the backend (`mvn -q compile`)

## Summary
PostgreSQL schema for the source data, configuration, day-end snapshots, settlements, outbox/letters and the audit trail; a trigger that makes the audit trail append-only; JDBC code that loads the data pack into the database, imports payment and contact files duplicate-safely, and reads the whole book back for the engine; Docker Compose for the full stack.

## Files
| File | Role |
|---|---|
| `db/migration/V1__schema.sql` | All tables, keys and indexes (money as NUMERIC) |
| `db/migration/V2__audit_append_only.sql` | Trigger: UPDATE, DELETE, TRUNCATE on audit_log raise an error |
| `data/BookRepository.java` | DB ↔ in-memory Book; batch inserts with ON CONFLICT DO NOTHING |
| `data/ImportService.java` | Seed from the data folder, import payment/contact files, import the evaluation files |
| `docker-compose.yml`, `*/Dockerfile`, `frontend/nginx.conf` | db, ml, backend, frontend containers |

## Data model (key points)
- Composite keys: `repayment_schedule (loan_id, instalment_no)`, `loan_positions (as_of_date, loan_id)`.
- `payments.payment_id` and `contacts.contact_id` are primary keys, so a file imported twice adds nothing.
- `loan_positions` keeps one snapshot per date: the roll-rate matrix compares two dates.
- `settlements.version` supports optimistic locking (409 on a race).
- `audit_log` is insert-only, enforced by the database.

## Tested
Both migrations applied cleanly on PostgreSQL 16; `UPDATE audit_log` returned `ERROR: audit_log is append-only: UPDATE is not allowed`.

## Run
```
docker compose up -d db          # PostgreSQL on 5432, db/user/password recoverplus
cd backend && mvn spring-boot:run   # Flyway applies the migrations on start
```

## Change scenarios
1. Drop the trigger: the audit trail becomes editable; a judge asking "can an admin change history?" now gets "yes".
2. Remove ON CONFLICT DO NOTHING from payments: re-importing the evaluation file fails on the primary key instead of skipping duplicates.
3. Change NUMERIC to DOUBLE PRECISION: amounts can drift by a paisa and stop matching the expected file.

**Driver:** [Data owner name]
