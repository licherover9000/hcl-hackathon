# Model card: miss-next-instalment (RecoverPlus)

- **Purpose:** estimate p_miss_next, the probability that a loan's next instalment is still more than ₹100 unpaid 7 days after its due date. Used only to order worklists (expected recovery) and to suggest pre-due reminders (R13) for CURRENT loans. Never decides holds, legal notices, settlements, field visits or contact limits.
- **Model:** logistic regression on standardised numeric features and one-hot product, occupation_type and bucket. Version `lr-202610230850`, seed 42. Gradient boosting trained as a comparison only.
- **Data:** monthly snapshots 2025-03-31 to 2026-02-28. Time-based split: train ≤ 2025-11-30, test 2025-12-31 to 2026-02-28, so no future information leaks into training.
- **Excluded features:** loan_id, snapshot_date, city_tier (city_tier is used only to check disparate impact). No names, phone numbers, gender or similar attributes are used.
- **Explainability:** each score lists its top 3 factors. For a linear model, coefficient × (value − training mean) per feature is the exact SHAP value; we show the 3 that push the score up most, in plain language.
- **Performance:** ROC-AUC 0.8836 vs baseline 0.7955; PR-AUC 0.8334; Brier 0.1012; precision at top 20% 0.9094. Full tables in output/evaluation_report.md.
- **Threshold rationale (0.60):** set by the case for R13. Above it a CURRENT loan only gets a polite pre-due reminder, a low-cost action, so a false alarm costs little while an early nudge can prevent a miss.

## AI-3: fairness and calibration

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

Calibration: see the 10-bin table in the evaluation report; predicted and actual rates should track each other.

## Fallback and governance
- If the scoring service is down or slower than 2 seconds, the backend keeps the rules order and the UI shows "AI ranking unavailable".
- model_version is stored with every score; training is one command with a fixed seed, so any score can be reproduced.
