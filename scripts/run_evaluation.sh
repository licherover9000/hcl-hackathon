#!/usr/bin/env bash
# RecoverPlus evaluation run (macOS / Linux / Git Bash). No database or Spring needed.
# Usage from repo root: bash scripts/run_evaluation.sh 2026-03-31        (sample check vs expected file)
#                       bash scripts/run_evaluation.sh 2026-04-15 --eval (evaluation data on top)
set -euo pipefail
AS_OF="${1:-2026-03-31}"; EVAL="${2:-}"; DATA="${DATA:-data/input}"; OUT="${OUT:-output}"
mkdir -p build/offline
javac -d build/offline $(find backend/src/main/java -name '*.java' \( -path '*/engine/*' -o -path '*/io/*' -o -name OfflineEvaluation.java \))
ARGS=(--data "$DATA" --as-of "$AS_OF" --out "$OUT")
[ "$EVAL" = "--eval" ] && ARGS+=(--eval)
[ -f "$OUT/ai_scores.csv" ] && ARGS+=(--scores "$OUT/ai_scores.csv")
java -cp build/offline com.threepointers.recoverplus.batch.OfflineEvaluation "${ARGS[@]}"
if [ "$EVAL" != "--eval" ]; then
  EXP=$(ls "$DATA"/*expected_collections* 2>/dev/null | head -1 || true)
  [ -n "$EXP" ] && python3 scripts/compare_expected.py "$OUT/collections_output.csv" "$EXP" || true
  REQ=$(ls "$DATA"/*settlement_requests_sample* 2>/dev/null | head -1 || true)
  [ -n "$REQ" ] && python3 scripts/compare_expected.py "$OUT/settlement_decisions.csv" "$REQ" --key request_id --prefix expected_ || true
fi
