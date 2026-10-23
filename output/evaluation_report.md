# RecoverPlus evaluation report (AI track)

Model version `lr-202610230850` · seed 42 · train snapshots up to 2025-11-30 (11682 rows) ·
test snapshots 2025-12-31 to 2026-02-28 (6347 rows) · features: 21
(excluded: loan_id, snapshot_date, city_tier).

## AI-1 metrics on the test months

| Metric | Logistic regression (served) | Gradient boosting (comparison) | Baseline: bucket miss rate |
|---|---|---|---|
| ROC-AUC | 0.8836 | 0.8932 | 0.7955 |
| PR-AUC | 0.8334 | 0.8721 | 0.6830 |
| Brier | 0.1012 | 0.0931 | 0.1341 |
| Precision@top20% | 0.9094 | 0.9377 | 0.8534 |
| Precision@0.6 | 0.8815 | 0.8923 | 0.8397 |
| Recall@0.6 | 0.7414 | 0.7560 | 0.6450 |

Reference target (ROC-AUC ≥ 0.85 and ≥ 0.05 above baseline): **met**
(model 0.8836, baseline 0.7955).

ROC-AUC on CURRENT accounts only (early warning): model **0.7365**, baseline 0.5000.

## Calibration (logistic regression, 10 bins)

| predicted p bin | rows | mean predicted | actual miss rate |
|---|---|---|---|
| 0.0-0.1 | 2439 | 0.078 | 0.090 |
| 0.1-0.2 | 1475 | 0.133 | 0.106 |
| 0.2-0.3 | 290 | 0.240 | 0.152 |
| 0.3-0.4 | 123 | 0.346 | 0.171 |
| 0.4-0.5 | 93 | 0.451 | 0.312 |
| 0.5-0.6 | 138 | 0.554 | 0.572 |
| 0.6-0.7 | 199 | 0.652 | 0.734 |
| 0.7-0.8 | 332 | 0.757 | 0.864 |
| 0.8-0.9 | 389 | 0.851 | 0.853 |
| 0.9-1.0 | 869 | 0.955 | 0.934 |

## AI-3 fairness (test months, threshold 0.6)

### By city_tier

| city_tier | rows | mean p_miss_next | actual miss rate | R13 flag rate |
|---|---|---|---|---|
| METRO | 2624 | 0.343 | 0.345 | 0.048 |
| NON_METRO | 3723 | 0.338 | 0.328 | 0.050 |

Lower / higher flag rate = **0.96** → OK (ratio >= 0.8)
### By occupation_type

| occupation_type | rows | mean p_miss_next | actual miss rate | R13 flag rate |
|---|---|---|---|---|
| SALARIED | 4088 | 0.333 | 0.324 | 0.053 |
| SELF_EMPLOYED | 2259 | 0.352 | 0.355 | 0.042 |

Lower / higher flag rate = **0.78** → BELOW 0.8: compare with actual_miss; if the gap follows actual payment behaviour it reflects risk, not bias

