# Step 07: Results on the real data pack

**Status:** done. All outputs generated from `case_03_RecoverPlus_V1.0.zip`.

## 1. Check against the expected sample (as of 2026-03-31)
`scripts/run_evaluation.ps1 -AsOf 2026-03-31`
- collections_output.csv vs case_03_expected_collections_sample: **2,310 / 2,310 rows match on every column** (dpd, bucket, overdue, penal, total dues, strategy, queue, codes, worklist_rank).
- settlement_decisions.csv vs case_03_settlement_requests_sample: **12 / 12 match** (eligible, discount_pct, route, reason_code).
- Buckets: CURRENT 1,724 · SMA_0 271 · SMA_1 127 · SMA_2 64 · NPA 124. Runtime about 1.5 s for 2,310 loans.
- All assumptions A1 to A8 held: no mismatch to fix.

## 2. Evaluation run (as of 2026-04-15, evaluation files on top)
`scripts/run_evaluation.ps1 -AsOf 2026-04-15 -Eval`
- Loaded 1,247 evaluation payments (0 duplicates), 914 evaluation contacts, 25 settlement requests.
- Buckets: CURRENT 1,708 · SMA_0 233 · SMA_1 166 · SMA_2 69 · NPA 134.
- Deliverables: `output/collections_output.csv` (2,310 rows), `output/settlement_decisions.csv` (25 rows).

## 3. AI track (real training snapshots)
`python ml/train.py --data data/input --out output`
- Train 11,682 snapshots (32.0% missed), test 6,347 (33.5%).
- Logistic regression ROC-AUC **0.8836** vs bucket baseline **0.7955** → target (≥ 0.85 and ≥ 0.05 above baseline) **met**. PR-AUC 0.8334, Brier 0.1012, precision at top 20% 0.9094.
- ROC-AUC on CURRENT loans only: 0.7365 (baseline 0.5000, since all CURRENT loans share one bucket rate).
- Gradient boosting comparison: ROC-AUC 0.8932; we serve logistic regression for exact explanations.
- Calibration and fairness tables: `output/evaluation_report.md`; model card: `ml/model_card.md`.
- `output/ai_scores.csv` → `output/risk_scores.csv` (p_miss_next, expected_recovery, ai_rank, top_factors) for the 2026-04-15 positions.

## Change made in this step
`engine/RulesConfig.java`: bucket limits are read from the rules JSON `buckets.SMA_0/SMA_1/SMA_2` arrays (upper bound). Every other key in the file already matched. Only `ptpMaxDaysAhead` (15 days) uses the case default, because the JSON has no key for it. Worked examples still 13 / 13.

## Panel talking points
- "We proved correctness, not claimed it: 2,310 of 2,310 rows identical to HCLTech's expected file."
- "The AI beats the rule baseline by 0.09 ROC-AUC, and only re-orders worklists; rules stay authoritative."
