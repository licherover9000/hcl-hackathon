"""Scoring service for the Spring Boot backend (case_03 AI-2).

Run from the repo root:  uvicorn serve:app --app-dir ml --port 8001
Env: FEATURES_FILE (default: the features_current CSV in data/input)

GET  /health              -> {"status": "UP", "model_version": ...}
POST /score {"loan_ids": ["L900001", ...]}  (omit loan_ids to score every loan)
     -> {"model_version": ..., "scores": [{"loan_id", "p_miss_next", "top_factors": [3 names], "reasons": [3 plain-language lines]}]}
POST /score/rows {"rows": [{feature: value, ...}]}  scores arbitrary feature rows (same response shape, loan_id if given)
"""
import os
from pathlib import Path
from typing import List, Optional

import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from common import explain, load_model, plain_text

app = FastAPI(title="RecoverPlus ML scoring", version="1.0")
PIPE, CATS, NUMS, VERSION = load_model()
FEATS = CATS + NUMS


def _features_file() -> Optional[Path]:
    env = os.environ.get("FEATURES_FILE")
    if env:
        return Path(env)
    root = Path(__file__).resolve().parent.parent / "data" / "input"
    hits = sorted(root.glob("*features_current*.csv")) if root.exists() else []
    return hits[0] if hits else None


FEATURES = None
_f = _features_file()
if _f and _f.exists():
    FEATURES = pd.read_csv(_f).set_index("loan_id", drop=False)


class ScoreRequest(BaseModel):
    loan_ids: Optional[List[str]] = None


class RowsRequest(BaseModel):
    rows: List[dict]


def _score(df: pd.DataFrame):
    p = PIPE.predict_proba(df[FEATS])[:, 1]
    tops = explain(PIPE, CATS, NUMS, df[FEATS])
    out = []
    for i, (_, row) in enumerate(df.iterrows()):
        out.append({
            "loan_id": row.get("loan_id"),
            "p_miss_next": round(float(p[i]), 4),
            "top_factors": tops[i],
            "reasons": [plain_text(f, row.get(f)) for f in tops[i]],
        })
    return out


@app.get("/health")
def health():
    return {"status": "UP", "model_version": VERSION, "loans_loaded": 0 if FEATURES is None else len(FEATURES)}


@app.post("/score")
def score(req: ScoreRequest):
    if FEATURES is None:
        raise HTTPException(503, "features_current file not loaded; set FEATURES_FILE")
    df = FEATURES if not req.loan_ids else FEATURES.loc[[i for i in req.loan_ids if i in FEATURES.index]]
    return {"model_version": VERSION, "scores": _score(df) if len(df) else []}


@app.post("/score/rows")
def score_rows(req: RowsRequest):
    df = pd.DataFrame(req.rows)
    missing = [c for c in FEATS if c not in df.columns]
    if missing:
        raise HTTPException(422, f"missing features: {missing}")
    df = df.replace({None: np.nan})
    return {"model_version": VERSION, "scores": _score(df)}
