# Step 01 (P2): Day-end calculation engine

**Owner:** Backend · **Status:** done, unit-tested

## Summary
The calculation core of RecoverPlus, written as plain Java classes with no Spring and no database. It turns a loan's schedule, payments and contacts into its position, collection strategy, queue, codes and worklist rank, and decides settlements. Both the offline evaluation run and the Spring API call this same code, so the CSV outputs and the screens can never disagree.

## Files
| File | Role |
|---|---|
| `backend/pom.xml` | Spring Boot 3.3 / Java 21 build (web, jdbc, validation, postgres, flyway, springdoc) |
| `engine/EngineModels.java` | Records: LoanTerms, Instalment, PaymentRec, ContactRec, Position, RuleResult, SettlementDecision |
| `engine/RulesConfig.java` | Every threshold; read from the rules_config table, case defaults if a key is missing; `defaultsUsed()` lists any fallback |
| `engine/PositionCalculator.java` | FIFO allocation, overdue check, DPD, bucket, overdue, penal, total dues, PTP status |
| `engine/CollectionRules.java` | R3 to R8 base strategy, R9 escalation, R10 senior queue, R11, R12, holds R1/R2, R13 early warning |
| `engine/WorklistRanker.java` | Rules order and AI order ranking per queue |
| `engine/SettlementEvaluator.java` | Eligibility, discount %, routing |
| `engine/DayEndProcessor.java` | Runs all loans for a date, ranks, assigns collectors round-robin within capacity, evaluates settlement requests |
| `batch/OfflineEvaluation.java` | Command-line run: data pack in, output CSVs out (no DB) |
| `test/.../WorkedExamplesTest.java` | Worked examples 1 to 14 plus ranking |

## How it works (one loan)
1. `PositionCalculator.calculate`: payments dated on or before as-of, sorted by date then payment_id, fill the earliest instalment with anything unpaid (future ones too); excess is ignored.
2. An instalment is overdue when its due date is before as-of and unpaid is above ₹100. DPD = as-of minus the oldest overdue due date.
3. Penal = Σ unpaid × 24% × days overdue ÷ 365, summed unrounded, rounded HALF_UP once. Total dues = overdue + penal + closing principal of the last instalment due before as-of (loan principal if none due).
4. PTPs: broken when ptp_date + 2 is before as-of and payments from the contact date to ptp_date + 2 are below the promise; counted if ptp_date is within the 60 days before as-of.
5. `CollectionRules.apply`: base strategy by bucket, then escalation, queue override, notices, skip trace, then holds last. Codes are reported in the fixed order of section 6.1.
6. `WorklistRanker`: per queue, escalated first, DPD desc, overdue desc, loan_id asc.

## Design decisions
- **Pure Java engine:** the rules are unit-testable in milliseconds and explainable line by line. Alternative was SQL window functions: faster on huge data, harder to test and explain.
- **BigDecimal with DECIMAL128 and one final rounding:** paisa-exact penal and dues. `double` would drift.
- **Config lookup by key name with defaults:** the rules JSON key names are matched by suffix; any default used is printed, so a mismatch is visible, never silent.

## Assumptions (to confirm against the expected file)
- A1: a hold also applies to a CURRENT loan (it goes to the hold desk with a rank). Switch: `CollectionRules.HOLD_APPLIES_TO_CURRENT`.
- A2: R9 escalation only applies when there is a strategy on the ladder (not NONE or HOLD).
- A3: "broken PTPs in the 60 days before as-of" includes ptp_date = as-of − 60 and excludes as-of itself.
- A4: R12 needs at least 3 contacts before as-of.
- A5: discount_pct is reported for every settlement request, eligible or not.

## Run and test
- Unit tests: `cd backend && mvn test` (needs Maven Central). Verified here with plain `javac`: **13 tests, 13 passed** (worked examples 1 to 14).
- Full offline run on the 10-loan sample pack: 10 / 10 loans and 6 / 6 settlements match the worked examples.

## Change scenarios a panelist may try
1. Change `penalRatePct` in config to 18: penal and total dues change for every overdue loan on the next run; no code change. Look at `PositionCalculator` step 3.
2. Change the tolerance check `> tolerance` to `>=`: a loan exactly ₹100 short becomes overdue (example 8 is ₹50 short, so it still passes). Look at `PositionCalculator`, the `isOverdue` line.
3. Move the hold block above R9: the code list still contains BROKEN_PTP_REPEAT but the queue stays the hold desk; test `example9` still passes, and the order of `apply` is what defines precedence.

**Driver:** Pranjal (with Claude as coding assistant)
