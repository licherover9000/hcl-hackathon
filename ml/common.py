"""Shared feature definitions, model loading and plain-language explanations."""
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

ARTIFACTS = Path(__file__).parent / "artifacts"
LABEL = "missed_next_instalment"
# Excluded by the case (AI-1): identifiers, the snapshot date, and city_tier (fairness check only).
EXCLUDED = {"loan_id", "snapshot_date", "as_of_date", "city_tier", LABEL}
CATEGORICAL = ["product", "occupation_type", "bucket"]
NUMERIC = ["bureau_score_at_origination", "monthly_income", "emi_amount", "emi_to_income", "interest_rate_pa",
           "months_on_book", "instalments_remaining", "dpd", "overdue_amount", "missed_6m", "max_dpd_12m",
           "on_time_ratio_12m", "days_since_last_payment", "last_payment_ratio", "broken_ptp_90d",
           "ptp_count_90d", "contacts_30d", "rpc_30d"]

PLAIN = {
    "broken_ptp_90d": "{v:.0f} broken promises in the last 90 days",
    "ptp_count_90d": "{v:.0f} promises to pay in the last 90 days",
    "missed_6m": "{v:.0f} instalments missed in the last 6 months",
    "max_dpd_12m": "up to {v:.0f} days past due in the last 12 months",
    "on_time_ratio_12m": "paid on time in {pct:.0f}% of the last 12 months",
    "days_since_last_payment": "{v:.0f} days since the last payment",
    "last_payment_ratio": "last payment was {pct:.0f}% of the EMI",
    "dpd": "currently {v:.0f} days past due",
    "overdue_amount": "₹{v:,.0f} overdue",
    "emi_to_income": "EMI is {pct:.0f}% of monthly income",
    "contacts_30d": "{v:.0f} contacts in the last 30 days",
    "rpc_30d": "{v:.0f} conversations with the borrower in 30 days",
    "bureau_score_at_origination": "bureau score {v:.0f} at origination",
    "months_on_book": "{v:.0f} months on book",
    "instalments_remaining": "{v:.0f} instalments remaining",
    "monthly_income": "monthly income ₹{v:,.0f}",
    "emi_amount": "EMI ₹{v:,.0f}",
    "interest_rate_pa": "interest rate {v:.2f}%",
}


def feature_columns(df: pd.DataFrame):
    cats = [c for c in CATEGORICAL if c in df.columns]
    nums = [c for c in NUMERIC if c in df.columns]
    return cats, nums


def load_model():
    bundle = joblib.load(ARTIFACTS / "model.joblib")
    return bundle["pipeline"], bundle["categorical"], bundle["numeric"], bundle["version"]


def plain_text(feature: str, value) -> str:
    try:
        v = float(value)
    except (TypeError, ValueError):
        return f"{feature.replace('_', ' ')}: {value}"
    tpl = PLAIN.get(feature)
    if tpl is None:
        return f"{feature.replace('_', ' ')} = {v:g}"
    return tpl.format(v=v, pct=v * 100 if v <= 1.5 else v)


def explain(pipeline, cats, nums, X: pd.DataFrame, top_n: int = 3):
    """Per-row contribution of each original feature to the log-odds of missing.

    For logistic regression the contribution of a transformed column is coef x (x - mean of x in training);
    summed back to its original feature this is the exact linear SHAP value. Returns the top_n features
    that push the score UP (towards a miss), most important first."""
    pre = pipeline.named_steps["prep"]
    clf = pipeline.named_steps["clf"]
    Xt = pre.transform(X)
    Xt = Xt.toarray() if hasattr(Xt, "toarray") else np.asarray(Xt)
    names = pre.get_feature_names_out()
    contrib = (Xt - pipeline.train_mean_) * clf.coef_[0]
    owners = []
    for n in names:
        base = n.split("__", 1)[1]
        owner = next((c for c in cats if base.startswith(c + "_")), base)
        owners.append(owner)
    owners = np.array(owners)
    feats = list(dict.fromkeys(owners))
    per_feat = np.column_stack([contrib[:, owners == f].sum(axis=1) for f in feats])
    out = []
    for i in range(per_feat.shape[0]):
        order = np.argsort(-per_feat[i])
        top = [feats[j] for j in order[:top_n]]
        out.append(top)
    return out
