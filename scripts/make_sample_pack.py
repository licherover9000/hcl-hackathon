"""Builds a small synthetic data pack (loans L900001-L900010, settlements S0001-S0006)
from the case's worked examples, in the same file names and columns as the real pack.
Used to test the pipeline without the real data. Usage: python scripts/make_sample_pack.py OUT_DIR"""
import csv, json, sys, pathlib
from datetime import date
from decimal import Decimal as D, ROUND_HALF_UP

out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "data/sample_pack"); out.mkdir(parents=True, exist_ok=True)
AS_OF = date(2026, 3, 31)

def add_months(d, n):
    m = d.month - 1 + n
    return date(d.year + m // 12, m % 12 + 1, d.day)

def schedule(principal, tenure, emi, day):
    rate, bal, rows = D(14) / D(1200), D(principal), []
    for i in range(1, tenure + 1):
        due = add_months(date(2025, 4, day), i - 1)
        opening = bal
        interest = (bal * rate).quantize(D("0.01"), ROUND_HALF_UP)
        bal = bal - (D(emi) - interest)
        rows.append((i, due, D(emi), D(emi) - interest, interest, opening, max(bal, D(0))))
    return [r for r in rows if r[1] <= date(2026, 6, 30)]

loans = {  # id: (principal, tenure, emi, due_day, unpaid due dates, partial {date: amount}, dispute)
 "L900001": (300000, 36, 10253, 15, [], {}, "N"),
 "L900002": (300000, 36, 10253, 15, [date(2026,3,15)], {}, "N"),
 "L900003": (300000, 36, 10253, 25, [date(2026,3,25)], {}, "N"),
 "L900004": (300000, 36, 10253, 15, [date(2026,2,15), date(2026,3,15)], {}, "N"),
 "L900005": (300000, 36, 10253, 15, [date(2026,2,15), date(2026,3,15)], {}, "N"),
 "L900006": (300000, 36, 10253, 10, [date(2026,1,10), date(2026,2,10), date(2026,3,10)], {}, "N"),
 "L900007": (900000, 48, 24594, 5, [date(2025,11,5), date(2025,12,5), date(2026,1,5), date(2026,2,5), date(2026,3,5)], {}, "N"),
 "L900008": (300000, 36, 10253, 15, [], {date(2026,3,15): D(10203)}, "N"),
 "L900009": (300000, 36, 10253, 15, [date(2026,3,15)], {}, "Y"),
 "L900010": (300000, 36, 10253, 15, [date(2026,3,15)], {}, "N"),
}
W = lambda name: csv.writer(open(out / name, "w", newline=""))
lw, cw, sw, pw = W("case_03_loans_V1.0.csv"), W("case_03_customers_V1.0.csv"), W("case_03_repayment_schedule_V1.0.csv"), W("case_03_payments_V1.0.csv")
lw.writerow("loan_id,customer_id,product,disbursal_date,principal,interest_rate_pa,tenure_months,emi_amount,emi_due_day,first_due_date,branch,bureau_score_at_origination".split(","))
cw.writerow("customer_id,full_name,mobile,city,preferred_language,occupation_type,monthly_income,dispute_flag,deceased_flag".split(","))
sw.writerow("loan_id,instalment_no,due_date,emi_amount,principal_component,interest_component,opening_principal,closing_principal".split(","))
pw.writerow("payment_id,loan_id,payment_date,amount,channel".split(","))
pn = 0
for lid, (p, t, emi, day, unpaid, partial, dispute) in loans.items():
    cid = "CU" + lid[1:]
    lw.writerow([lid, cid, "PERSONAL_LOAN", "2025-03-20", p, "14.00", t, emi, day, date(2025,4,day).isoformat(), "MUMBAI_MAIN", 720])
    cw.writerow([cid, f"Customer {lid}", "9812345610", "Mumbai", "EN", "SALARIED", 60000, dispute, "N"])
    for (i, due, e, pc, ic, op, cl) in schedule(p, t, emi, day):
        sw.writerow([lid, i, due.isoformat(), e, pc, ic, op, cl])
        if due <= AS_OF and due not in unpaid:
            pn += 1
            pw.writerow([f"PAY{pn:06d}", lid, due.isoformat(), partial.get(due, e), "NACH"])
ch = W("case_03_contact_history_V1.0.csv")
ch.writerow("contact_id,loan_id,contact_ts,channel,outcome,ptp_date,ptp_amount,collector_id".split(","))
ch.writerow(["CT0001", "L900005", "2026-03-10T11:00:00+05:30", "CALL", "PTP", "2026-03-14", 10253, "U401"])
ch.writerow(["CT0002", "L900010", "2026-02-20T11:00:00+05:30", "CALL", "PTP", "2026-02-24", 10253, "U401"])
ch.writerow(["CT0003", "L900010", "2026-03-18T11:00:00+05:30", "CALL", "PTP", "2026-03-22", 10253, "U401"])
u = W("case_03_users_V1.0.csv"); u.writerow("user_id,full_name,role,queue,city,daily_capacity".split(","))
for r in [("U401","Asha Telecaller","COLLECTOR","TELECALLER","Mumbai",40),("U402","Ravi Field","COLLECTOR","FIELD","Mumbai",15),
          ("U403","Meena Senior","COLLECTOR","SENIOR_COLLECTOR","Mumbai",10),("U404","Kiran Recovery","COLLECTOR","RECOVERY_TEAM","Mumbai",10),
          ("U501","Manoj Manager","COLLECTIONS_MANAGER","","Mumbai",0),("U601","Hema Head","HEAD_OF_COLLECTIONS","","Mumbai",0),
          ("U701","Arun Auditor","AUDITOR","","Mumbai",0)]:
    u.writerow(r)
s = W("case_03_settlement_requests_sample_V1.0.csv")
s.writerow("request_id,loan_id,request_date,proposed_by,offer_amount,expected_eligible,expected_discount_pct,expected_route,expected_reason_code".split(","))
for r in [("S0001","L900007","2026-03-31","U401",722042,"Y","15.00","COLLECTIONS_MANAGER",""),
          ("S0002","L900007","2026-03-31","U401",594623,"Y","30.00","HEAD_OF_COLLECTIONS",""),
          ("S0003","L900007","2026-03-31","U401",467204,"Y","45.00","REJECT","DISCOUNT_ABOVE_LIMIT"),
          ("S0004","L900004","2026-03-31","U401",200000,"N","","REJECT","NOT_ELIGIBLE_BUCKET"),
          ("S0005","L900006","2026-03-31","U401",220794.14,"Y","10.00","COLLECTIONS_MANAGER",""),
          ("S0006","L900009","2026-03-31","U401",200000,"N","","REJECT","ACCOUNT_ON_HOLD")]:
    s.writerow(r)
json.dump({"tolerance_inr": 100, "penal_rate_pa": 24, "digital_max_dpd": 7, "high_value_total_dues": 500000},
          open(out / "case_03_rules_config_V1.0.json", "w"), indent=2)
e = W("case_03_expected_collections_sample_V1.0.csv")
e.writerow("loan_id,dpd,bucket,overdue_amount,penal_charges,total_dues,contact_strategy,queue,codes,worklist_rank".split(","))
for r in [("L900001",0,"CURRENT","0.00","0.00","213556.57","NONE","","",""),
          ("L900002",16,"SMA_0","10253.00","107.87","223917.44","TELECALL","TELECALLER","",""),
          ("L900003",6,"SMA_0","","40.45","","DIGITAL_REMINDER","AUTO_DIGITAL","",""),
          ("L900004",44,"SMA_1","20506.00","404.50","","TELECALL","TELECALLER","",""),
          ("L900005",44,"SMA_1","","","","FIELD_VISIT","FIELD","",""),
          ("L900006",80,"SMA_2","30759.00","1011.25","","FIELD_VISIT","FIELD","PRE_LEGAL_NOTICE",""),
          ("L900007",146,"NPA","122970.00","6905.19","849461.47","RECOVERY","SENIOR_COLLECTOR","HIGH_VALUE|NPA_CLASSIFIED",""),
          ("L900008",0,"CURRENT","","","","NONE","","",""),
          ("L900009",16,"SMA_0","","","","HOLD","DISPUTE_DESK","HOLD_DISPUTE",""),
          ("L900010",16,"SMA_0","","","","FIELD_VISIT","FIELD","BROKEN_PTP_REPEAT","")]:
    e.writerow(r)
print("sample pack written to", out)
