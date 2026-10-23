"""Compare our output CSV with the case's expected CSV, column by column.

Usage:
  python scripts/compare_expected.py output/collections_output.csv data/input/case_03_expected_collections_sample_V1.0.csv
  python scripts/compare_expected.py output/settlement_decisions.csv data/input/case_03_settlement_requests_sample_V1.0.csv --key request_id --prefix expected_

Numbers are compared as decimals (0.00 == 0), text exactly; codes compared as written.
--ignore-blank-expected skips cells that are empty in the expected file (only for partial test fixtures).
Exit code 0 when every compared cell matches.
"""
import argparse
import csv
import sys
from collections import Counter
from decimal import Decimal, InvalidOperation


def norm(v):
    v = (v or "").strip()
    try:
        return str(Decimal(v).normalize()) if v else ""
    except InvalidOperation:
        return v


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ours")
    ap.add_argument("expected")
    ap.add_argument("--key", default="loan_id")
    ap.add_argument("--prefix", default="", help="column prefix in the expected file, e.g. expected_")
    ap.add_argument("--ignore-blank-expected", action="store_true")
    a = ap.parse_args()

    ours = {r[a.key]: r for r in csv.DictReader(open(a.ours, newline="", encoding="utf-8-sig"))}
    exp_rows = list(csv.DictReader(open(a.expected, newline="", encoding="utf-8-sig")))
    cols = [c for c in next(iter(ours.values())).keys() if c != a.key]

    mismatches, examples, compared, missing = Counter(), {}, 0, []
    for e in exp_rows:
        k = e[a.key]
        o = ours.get(k)
        if o is None:
            missing.append(k)
            continue
        compared += 1
        for c in cols:
            ec = a.prefix + c
            if ec not in e:
                continue
            if a.ignore_blank_expected and not (e[ec] or "").strip():
                continue
            if norm(o.get(c)) != norm(e[ec]):
                mismatches[c] += 1
                examples.setdefault(c, []).append((k, o.get(c), e[ec]))

    bad_rows = len({k for c in examples for (k, _, _) in examples[c]})
    print(f"Rows compared: {compared}  |  rows fully matching: {compared - bad_rows} / {compared}")
    if missing:
        print(f"Missing in our output: {len(missing)} (first: {missing[:5]})")
    for c in cols:
        if (a.prefix + c) in exp_rows[0]:
            status = "OK" if mismatches[c] == 0 else f"{mismatches[c]} mismatches"
            print(f"  {c:18s} {status}")
    for c, ex in examples.items():
        print(f"\nFirst mismatches in {c} (key, ours, expected):")
        for row in ex[:8]:
            print("  ", row)
    sys.exit(0 if not mismatches and not missing else 1)


if __name__ == "__main__":
    main()
