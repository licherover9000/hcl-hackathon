# Data folder

Put the case_03 data pack here, unchanged:

```
data/input/
  case_03_loans_V1.0.csv
  case_03_customers_V1.0.csv
  case_03_repayment_schedule_V1.0.csv
  case_03_payments_V1.0.csv
  case_03_contact_history_V1.0.csv
  case_03_users_V1.0.csv
  case_03_rules_config_V1.0.json
  case_03_expected_collections_sample_V1.0.csv
  case_03_settlement_requests_sample_V1.0.csv
  case_03_training_snapshots_V1.0.csv      (AI track)
  case_03_features_current_V1.0.csv        (AI track)
  # at Hour 5:30 add:
  case_03_payments_eval_V1.0.csv
  case_03_contact_history_eval_V1.0.csv
  case_03_settlement_requests_eval_V1.0.csv
```

Files are found by name: seed files are those without `eval` in the name; evaluation files have `eval`.
Outputs are written to `output/` at the repo root.

No data pack yet? `python scripts/make_sample_pack.py data/input` writes a 10-loan pack built from the case's worked examples.
