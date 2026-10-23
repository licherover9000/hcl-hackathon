# RecoverPlus ML (AI track)

Predicts `p_miss_next` (will the next instalment be missed?) for every loan, explains each score with its top 3 factors, and serves scores to the Spring Boot day-end.

## Setup (once)
```
python -m venv ml/.venv
ml\.venv\Scripts\activate            # Windows   (macOS/Linux: source ml/.venv/bin/activate)
pip install -r ml/requirements.txt
```

## 1. Train, evaluate and score the current book
```
python ml/train.py --data data/input --out output
```
Writes `ml/artifacts/model.joblib`, `output/evaluation_report.md`, `ml/model_card.md` and `output/ai_scores.csv`.

## 2. risk_scores.csv
The day-end turns `output/ai_scores.csv` into `output/risk_scores.csv` (adds expected_recovery and ai_rank within the rules queue):
```
powershell -ExecutionPolicy Bypass -File scripts\run_evaluation.ps1 -AsOf 2026-03-31
```
(the script picks up `output/ai_scores.csv` automatically when it exists).

## 3. Serve scores to the backend
```
uvicorn serve:app --app-dir ml --port 8001
```
`GET /health`, `POST /score {"loan_ids": [...]}`. The backend calls it with a 2-second timeout; if it is down, worklists fall back to the rules order.

## Design
- Logistic regression (served) on standardised numeric features plus one-hot product, occupation_type, bucket. Gradient boosting trained as a comparison.
- Time-based split (train ≤ 2025-11-30, test Dec 2025 to Feb 2026), seed 42; loan_id, snapshot_date and city_tier excluded.
- Top factors: coefficient × (value − training mean) per feature = exact SHAP value for a linear model.
- Baseline: each bucket's miss rate on the training months.
