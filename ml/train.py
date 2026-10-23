"""Train and evaluate the miss-next-instalment model (case_03 AI-1, AI-3), then score the current book.

Usage (from the repo root):
  python ml/train.py --data data/input --out output

Writes:
  ml/artifacts/model.joblib       trained pipeline (logistic regression)
  output/evaluation_report.md     AI-1 metrics vs baseline, calibration and fairness tables
  ml/model_card.md                model card with the AI-3 section
  output/ai_scores.csv            loan_id, p_miss_next, top_factors for the current book
                                  (the Java day-end turns this into risk_scores.csv with expected_recovery and ai_rank)
"""
import argparse
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, brier_score_loss, roc_auc_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from common import ARTIFACTS, LABEL, explain, feature_columns

SEED = 42
TRAIN_END = pd.Timestamp("2025-11-30")
TEST_START, TEST_END = pd.Timestamp("2025-12-31"), pd.Timestamp("2026-02-28")
THRESHOLD = 0.60


def find(data: Path, key: str) -> Path:
    hits = sorted(p for p in data.iterdir() if key in p.name.lower() and p.suffix == ".csv" and "eval" not in p.name.lower())
    if not hits:
        raise SystemExit(f"No file containing '{key}' in {data}")
    return hits[0]


def make_pipeline(cats, nums, model):
    prep = ColumnTransformer([
        ("num", Pipeline([("impute", SimpleImputer(strategy="median")), ("scale", StandardScaler())]), nums),
        ("cat", OneHotEncoder(handle_unknown="ignore", sparse_output=False), cats),
    ])
    return Pipeline([("prep", prep), ("clf", model)])


def precision_at_top(y, p, share=0.20):
    k = max(1, int(round(len(p) * share)))
    idx = np.argsort(-p)[:k]
    return float(np.mean(np.asarray(y)[idx]))


def metrics(y, p, threshold=THRESHOLD):
    pred = (p >= threshold).astype(int)
    tp = int(((pred == 1) & (y == 1)).sum())
    return {
        "ROC-AUC": roc_auc_score(y, p),
        "PR-AUC": average_precision_score(y, p),
        "Brier": brier_score_loss(y, p),
        "Precision@top20%": precision_at_top(y, p),
        f"Precision@{threshold}": tp / max(1, int(pred.sum())),
        f"Recall@{threshold}": tp / max(1, int((y == 1).sum())),
    }


def table(rows, headers):
    out = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data/input")
    ap.add_argument("--out", default="output")
    a = ap.parse_args()
    data, out = Path(a.data), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    ARTIFACTS.mkdir(parents=True, exist_ok=True)

    df = pd.read_csv(find(data, "training_snapshots"))
    df["snapshot_date"] = pd.to_datetime(df["snapshot_date"])
    cats, nums = feature_columns(df)
    feats = cats + nums
    train = df[df["snapshot_date"] <= TRAIN_END]
    test = df[(df["snapshot_date"] >= TEST_START) & (df["snapshot_date"] <= TEST_END)]
    ytr, yte = train[LABEL].astype(int).values, test[LABEL].astype(int).values
    print(f"Train rows {len(train)} (positive {ytr.mean():.1%}), test rows {len(test)} (positive {yte.mean():.1%})")

    # Primary model: logistic regression (explainable: contributions are exact).
    lr = make_pipeline(cats, nums, LogisticRegression(max_iter=3000, C=1.0, random_state=SEED))
    lr.fit(train[feats], ytr)
    Xtr_t = lr.named_steps["prep"].transform(train[feats])
    lr.train_mean_ = np.asarray(Xtr_t).mean(axis=0)
    p_lr = lr.predict_proba(test[feats])[:, 1]

    # Comparison model: gradient boosted trees (sklearn's LightGBM-style learner).
    gb = make_pipeline(cats, nums, HistGradientBoostingClassifier(random_state=SEED, max_iter=300, learning_rate=0.05))
    gb.fit(train[feats], ytr)
    p_gb = gb.predict_proba(test[feats])[:, 1]

    # Baseline: miss rate of each bucket learned on the training months.
    bucket_rate = train.groupby("bucket")[LABEL].mean()
    p_base = test["bucket"].map(bucket_rate).fillna(ytr.mean()).values

    m_lr, m_gb, m_base = metrics(yte, p_lr), metrics(yte, p_gb), metrics(yte, p_base)
    cur = (test["bucket"] == "CURRENT").values
    auc_cur = {
        "model": roc_auc_score(yte[cur], p_lr[cur]) if cur.sum() and len(set(yte[cur])) > 1 else float("nan"),
        "baseline": roc_auc_score(yte[cur], p_base[cur]) if cur.sum() and len(set(yte[cur])) > 1 else float("nan"),
    }

    # Calibration: 10 equal-width bins of predicted probability.
    bins = np.clip((p_lr * 10).astype(int), 0, 9)
    calib = []
    for b in range(10):
        m = bins == b
        if m.sum():
            calib.append((f"{b / 10:.1f}-{(b + 1) / 10:.1f}", int(m.sum()), f"{p_lr[m].mean():.3f}", f"{yte[m].mean():.3f}"))

    # Fairness (AI-3): mean p and share flagged by R13 (CURRENT with p >= threshold), by city_tier and occupation_type.
    fair_sections = []
    t = test.assign(p=p_lr, flag=((test["bucket"] == "CURRENT") & (p_lr >= THRESHOLD)).astype(int))
    for col in ["city_tier", "occupation_type"]:
        if col not in t.columns:
            continue
        g = t.groupby(col).agg(rows=("p", "size"), mean_p=("p", "mean"), actual_miss=(LABEL, "mean"), flag_rate=("flag", "mean"))
        rates = g["flag_rate"]
        ratio = (rates.min() / rates.max()) if rates.max() > 0 else 1.0
        note = "OK (ratio >= 0.8)" if ratio >= 0.8 else "BELOW 0.8: compare with actual_miss; if the gap follows actual payment behaviour it reflects risk, not bias"
        rows = [(k, int(r.rows), f"{r.mean_p:.3f}", f"{r.actual_miss:.3f}", f"{r.flag_rate:.3f}") for k, r in g.iterrows()]
        fair_sections.append(f"### By {col}\n\n" + table(rows, [col, "rows", "mean p_miss_next", "actual miss rate", "R13 flag rate"])
                             + f"\n\nLower / higher flag rate = **{ratio:.2f}** → {note}\n")

    version = "lr-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M")
    joblib.dump({"pipeline": lr, "categorical": cats, "numeric": nums, "version": version}, ARTIFACTS / "model.joblib")

    fmt = lambda d: {k: f"{v:.4f}" for k, v in d.items()}
    mrows = [(k, fmt(m_lr)[k], fmt(m_gb)[k], fmt(m_base)[k]) for k in m_lr]
    target_ok = m_lr["ROC-AUC"] >= 0.85 and m_lr["ROC-AUC"] - m_base["ROC-AUC"] >= 0.05
    report = f"""# RecoverPlus evaluation report (AI track)

Model version `{version}` · seed {SEED} · train snapshots up to {TRAIN_END.date()} ({len(train)} rows) ·
test snapshots {TEST_START.date()} to {TEST_END.date()} ({len(test)} rows) · features: {len(feats)}
(excluded: loan_id, snapshot_date, city_tier).

## AI-1 metrics on the test months

{table(mrows, ["Metric", "Logistic regression (served)", "Gradient boosting (comparison)", "Baseline: bucket miss rate"])}

Reference target (ROC-AUC ≥ 0.85 and ≥ 0.05 above baseline): **{"met" if target_ok else "NOT met"}**
(model {m_lr["ROC-AUC"]:.4f}, baseline {m_base["ROC-AUC"]:.4f}).

ROC-AUC on CURRENT accounts only (early warning): model **{auc_cur["model"]:.4f}**, baseline {auc_cur["baseline"]:.4f}.

## Calibration (logistic regression, 10 bins)

{table(calib, ["predicted p bin", "rows", "mean predicted", "actual miss rate"])}

## AI-3 fairness (test months, threshold {THRESHOLD})

{"".join(fair_sections)}
"""
    (out / "evaluation_report.md").write_text(report, encoding="utf-8")

    card = f"""# Model card: miss-next-instalment (RecoverPlus)

- **Purpose:** estimate p_miss_next, the probability that a loan's next instalment is still more than ₹100 unpaid 7 days after its due date. Used only to order worklists (expected recovery) and to suggest pre-due reminders (R13) for CURRENT loans. Never decides holds, legal notices, settlements, field visits or contact limits.
- **Model:** logistic regression on standardised numeric features and one-hot product, occupation_type and bucket. Version `{version}`, seed {SEED}. Gradient boosting trained as a comparison only.
- **Data:** monthly snapshots 2025-03-31 to 2026-02-28. Time-based split: train ≤ {TRAIN_END.date()}, test {TEST_START.date()} to {TEST_END.date()}, so no future information leaks into training.
- **Excluded features:** loan_id, snapshot_date, city_tier (city_tier is used only to check disparate impact). No names, phone numbers, gender or similar attributes are used.
- **Explainability:** each score lists its top 3 factors. For a linear model, coefficient × (value − training mean) per feature is the exact SHAP value; we show the 3 that push the score up most, in plain language.
- **Performance:** ROC-AUC {m_lr["ROC-AUC"]:.4f} vs baseline {m_base["ROC-AUC"]:.4f}; PR-AUC {m_lr["PR-AUC"]:.4f}; Brier {m_lr["Brier"]:.4f}; precision at top 20% {m_lr["Precision@top20%"]:.4f}. Full tables in output/evaluation_report.md.
- **Threshold rationale (0.60):** set by the case for R13. Above it a CURRENT loan only gets a polite pre-due reminder, a low-cost action, so a false alarm costs little while an early nudge can prevent a miss.

## AI-3: fairness and calibration

{"".join(fair_sections)}
Calibration: see the 10-bin table in the evaluation report; predicted and actual rates should track each other.

## Fallback and governance
- If the scoring service is down or slower than 2 seconds, the backend keeps the rules order and the UI shows "AI ranking unavailable".
- model_version is stored with every score; training is one command with a fixed seed, so any score can be reproduced.
"""
    (Path(__file__).parent / "model_card.md").write_text(card, encoding="utf-8")

    # Score the current book (features as of 2026-03-31).
    cur_df = pd.read_csv(find(data, "features_current"))
    p_cur = lr.predict_proba(cur_df[feats])[:, 1]
    tops = explain(lr, cats, nums, cur_df[feats])
    pd.DataFrame({"loan_id": cur_df["loan_id"], "p_miss_next": np.round(p_cur, 4),
                  "top_factors": ["|".join(t) for t in tops]}).to_csv(out / "ai_scores.csv", index=False)
    print(f"ROC-AUC model {m_lr['ROC-AUC']:.4f} vs baseline {m_base['ROC-AUC']:.4f} (gb {m_gb['ROC-AUC']:.4f})")
    print(f"Wrote {ARTIFACTS / 'model.joblib'}, {out / 'evaluation_report.md'}, ml/model_card.md, {out / 'ai_scores.csv'} ({len(cur_df)} loans)")


if __name__ == "__main__":
    main()
