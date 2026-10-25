#!/usr/bin/env python3
"""Accuracy evaluation for the Etherm query service.

Ground truth is computed here, in Python, straight from the raw FI fetch responses in
data/synthetic. It shares no code with the Java normalisers or QueryEngine: categories come from
this file's own merchant table, dates from valueDate rather than the timestamp, and sums use
Decimal. Each question is then sent to the running rag-service and the response is scored on:

  - intent:     the plan's intent matches the expected one
  - period:     the resolved date range matches the expected one
  - exact:      every expected figure (total, previous total, top-N items) matches to the paisa
  - citations:  precision and recall of cited record ids against the records that should be used
  - prose:      every rupee amount and percentage in the sentence equals a computed figure
  - refusal:    out-of-scope questions return no figures and cite nothing
  - consent:    answers under a single-provider consent cite nothing outside it, and a revoked
                consent is refused

Usage: python3 evaluation/evaluate.py [--url http://localhost:8084] [--as-of 2026-10-25]
The services must run with the same as-of date (ETHERM_AS_OF), as docker-compose does.
Standard library only.
"""

import argparse
import calendar
import json
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAISA = Decimal("0.01")

# ------------------------------------------------------------------------ ground truth

# The generator's merchants and narration prefixes, mapped to what they are.
DEBIT_KINDS = {
    "UPI/RENT/": "rent",
    "UPI/FRESHMART/": "groceries",
    "UPI/QUICKBITE/": "food",
    "UPI/CITYFUELS/": "fuel",
    "UPI/URBANCART/": "shopping",
    "UPI/POWERGRID/": "utilities",
    "UPI/METRORIDE/": "transport",
    "ATM/": "cash",
    "ACH/SIP/": "sip",
    "NEFT/DEMAT/": "share_purchase",
    "ACH/PREMIUM/": "premium_debit",
}
# Money moved into the user's own investments is not spending.
NOT_SPENDING = {"sip", "share_purchase"}


@dataclass
class Txn:
    record_id: str
    fip: str
    day: date
    amount: Decimal
    kind: str  # e.g. groceries, salary, premium, mf_buy, epf_contribution
    label: str = ""
    payee: str = ""


@dataclass
class Snapshot:
    record_id: str
    fip: str
    kind: str  # balance:<fitype>, holding:<fitype>, policy:<TERM|HEALTH>
    value: Decimal


@dataclass
class User:
    customer_id: str
    txns: list = field(default_factory=list)
    snapshots: list = field(default_factory=list)
    fips: set = field(default_factory=set)


def d(s):
    return Decimal(s)


def load_user(path):
    raw = json.loads(path.read_text())
    user = User(raw["customerId"])
    for fi in raw["FI"]:
        fip = fi["fipID"]
        user.fips.add(fip)
        for item in fi["data"]:
            acc = item["decryptedFI"]["account"]
            ref, kind, s = acc["linkedAccRef"], acc["type"], acc["summary"]
            txns = (acc.get("transactions") or {}).get("transaction") or []
            if kind == "deposit":
                user.snapshots.append(Snapshot(f"{ref}:SUMMARY", fip, "balance:DEPOSIT", d(s["currentBalance"])))
                for t in txns:
                    n = t["narration"]
                    if t["type"] == "CREDIT":
                        k = "salary" if "SALARY" in n else "interest" if "INT.PD" in n else "other_credit"
                    else:
                        k = next((v for p, v in DEBIT_KINDS.items() if n.startswith(p)), None)
                        if k is None:
                            raise ValueError(f"unknown narration {n!r}; extend DEBIT_KINDS")
                    parts = n.split("/")
                    payee = k if len(parts) < 3 else parts[1] if parts[0] == "UPI" else parts[2]
                    user.txns.append(Txn(f"{ref}:{t['txnId']}", fip, date.fromisoformat(t["valueDate"]),
                                         d(t["amount"]), k, n.split("/")[1] if "/" in n else n, payee))
            elif kind == "mutual_funds":
                user.snapshots.append(Snapshot(f"{ref}:SUMMARY", fip, "balance:MUTUAL_FUNDS", d(s["currentValue"])))
                for h in s["investment"]["holdings"]["holding"]:
                    v = (d(h["closingUnits"]) * d(h["nav"])).quantize(PAISA)
                    user.snapshots.append(Snapshot(f"{ref}:H:{h['isin']}", fip, "holding:MUTUAL_FUNDS", v))
                for t in txns:
                    k = "mf_buy" if t["type"] == "BUY" else "mf_sell"
                    user.txns.append(Txn(f"{ref}:{t['txnId']}", fip, date.fromisoformat(t["transactionDate"]),
                                         d(t["amount"]), k))
            elif kind == "equities":
                user.snapshots.append(Snapshot(f"{ref}:SUMMARY", fip, "balance:EQUITIES", d(s["currentValue"])))
                for h in s["investment"]["holdings"]["holding"]:
                    v = (d(h["units"]) * d(h["lastTradedPrice"])).quantize(PAISA)
                    user.snapshots.append(Snapshot(f"{ref}:H:{h['isin']}", fip, "holding:EQUITIES", v))
            elif kind == "epf":
                user.snapshots.append(Snapshot(f"{ref}:SUMMARY", fip, "balance:EPF", d(s["currentBalance"])))
                for t in txns:
                    k = "epf_contribution" if t["type"] == "CONTRIBUTION" else "epf_interest"
                    user.txns.append(Txn(f"{ref}:{t['txnId']}", fip, date.fromisoformat(t["txnDate"]),
                                         d(t["amount"]), k))
            elif kind == "insurance_policies":
                user.snapshots.append(Snapshot(f"{ref}:POLICY", fip, f"policy:{s['policyType']}", d(s["sumAssured"])))
                for t in txns:
                    user.txns.append(Txn(f"{ref}:{t['txnId']}", fip, date.fromisoformat(t["txnDate"]),
                                         d(t["amount"]), "premium", s["policyName"]))
            else:
                raise ValueError(f"unknown account type {kind}")
    return user


# ------------------------------------------------------------------------ periods

def month_end(y, m):
    return date(y, m, calendar.monthrange(y, m)[1])


def add_months(day, n):
    m = day.month - 1 + n
    return date(day.year + m // 12, m % 12 + 1, 1)


def periods(as_of):
    """Phrase -> (from, to), worked out independently of the Java parser."""
    this_month = date(as_of.year, as_of.month, 1)
    q_start = date(as_of.year, (as_of.month - 1) // 3 * 3 + 1, 1)
    fy = as_of.year if as_of.month >= 4 else as_of.year - 1
    last_month = add_months(this_month, -1)
    return {
        "last month": (last_month, this_month - timedelta(days=1)),
        "last quarter": (add_months(q_start, -3), q_start - timedelta(days=1)),
        "in the last 6 months": (add_months(this_month, -6), this_month - timedelta(days=1)),
        "in March 2026": (date(2026, 3, 1), month_end(2026, 3)),
        "between January and March 2026": (date(2026, 1, 1), month_end(2026, 3)),
        "in FY 2025-26": (date(2025, 4, 1), date(2026, 3, 31)),
        "this financial year": (date(fy, 4, 1), as_of),
        "since June 2026": (date(2026, 6, 1), as_of),
    }


def previous(frm, to):
    """The period a comparison is made against: whole months step back by whole months."""
    if frm.day == 1 and (to + timedelta(days=1)).day == 1:
        nxt = to + timedelta(days=1)
        months = (nxt.year - frm.year) * 12 + nxt.month - frm.month
        return add_months(frm, -months), frm - timedelta(days=1)
    days = (to - frm).days + 1
    return frm - timedelta(days=days), frm - timedelta(days=1)


# ------------------------------------------------------------------------ question set

SPEND_KINDS = {"rent", "groceries", "food", "fuel", "shopping", "utilities", "transport", "cash", "premium_debit"}

# (template, intent, kinds counted, extra) — {p} is replaced by a period phrase.
FLOW_QUESTIONS = [
    ("How much did I spend {p}?", "SPEND_TOTAL", SPEND_KINDS, {}),
    ("What were my total expenses {p}?", "SPEND_TOTAL", SPEND_KINDS, {}),
    ("How much money went out of my account {p}?", "SPEND_TOTAL", SPEND_KINDS, {}),
    ("How much did I spend on groceries {p}?", "SPEND_CATEGORY", {"groceries"}, {}),
    ("What did groceries cost me {p}?", "SPEND_CATEGORY", {"groceries"}, {}),
    ("How much did I spend eating out {p}?", "SPEND_CATEGORY", {"food"}, {}),
    ("What was my fuel bill {p}?", "SPEND_CATEGORY", {"fuel"}, {}),
    ("How much rent did I pay {p}?", "SPEND_CATEGORY", {"rent"}, {}),
    ("How much were my electricity bills {p}?", "SPEND_CATEGORY", {"utilities"}, {}),
    ("How much did I spend on cabs and the metro {p}?", "SPEND_CATEGORY", {"transport"}, {}),
    ("How much did I spend shopping {p}?", "SPEND_CATEGORY", {"shopping"}, {}),
    ("How much cash did I withdraw {p}?", "SPEND_CATEGORY", {"cash"}, {}),
    ("What did I spend on food and groceries {p}?", "SPEND_CATEGORY", {"food", "groceries"}, {}),
    ("How much salary did I receive {p}?", "INCOME", {"salary"}, {}),
    ("What was my income {p}?", "INCOME", {"salary"}, {}),
    ("How much money came in {p}?", "INCOME", {"salary"}, {}),
    ("How much interest did I earn on my savings {p}?", "INCOME", {"interest"}, {}),
    ("How much did I pay in insurance premiums {p}?", "PREMIUMS_PAID", {"premium"}, {}),
    ("What did my insurance cost {p}?", "PREMIUMS_PAID", {"premium"}, {}),
    ("How much did I invest in mutual funds {p}?", "MF_INVESTED", {"mf_buy"}, {}),
    ("How much went into my SIPs {p}?", "MF_INVESTED", {"mf_buy"}, {}),
    ("How much was contributed to my EPF {p}?", "EPF_CONTRIBUTIONS", {"epf_contribution"}, {}),
    ("What were my 3 biggest expenses {p}?", "TOP_EXPENSES", SPEND_KINDS, {"top": 3}),
    ("Show my top 5 expenses {p}", "TOP_EXPENSES", SPEND_KINDS, {"top": 5}),
    ("Did my grocery spending go up {p} compared to the period before?", "SPEND_CATEGORY", {"groceries"}, {"compare": True}),
    ("Did my insurance premiums increase {p} compared to the one before?", "PREMIUMS_PAID", {"premium"}, {"compare": True}),
    ("How did my spending {p} compare with the period before?", "SPEND_TOTAL", SPEND_KINDS, {"compare": True}),
    ("What is my savings rate {p}?", "SAVINGS_RATE", SPEND_KINDS, {"savings": True}),
    ("How much did I save {p}?", "SAVINGS_RATE", SPEND_KINDS, {"savings": True}),
    ("Where did I spend the most money {p}?", "TOP_MERCHANTS", SPEND_KINDS, {"merchants": 5}),
    ("Show my top 3 merchants {p}", "TOP_MERCHANTS", SPEND_KINDS, {"merchants": 3}),
    ("Show my spending month by month {p}", "SPEND_TREND", SPEND_KINDS, {"trend": True}),
    ("What was my average monthly spending {p}?", "SPEND_TREND", SPEND_KINDS, {"trend": True}),
]

SNAPSHOT_QUESTIONS = [
    ("What is my net worth?", "BALANCE", lambda s: s.kind.startswith("balance:")),
    ("How much am I worth across all my accounts?", "BALANCE", lambda s: s.kind.startswith("balance:")),
    ("What is my savings account balance?", "BALANCE", lambda s: s.kind == "balance:DEPOSIT"),
    ("How much money do I have in the bank?", "BALANCE", lambda s: s.kind == "balance:DEPOSIT"),
    ("What is my EPF balance?", "BALANCE", lambda s: s.kind == "balance:EPF"),
    ("How much are my mutual funds worth?", "BALANCE", lambda s: s.kind == "balance:MUTUAL_FUNDS"),
    ("What is my stock portfolio worth?", "BALANCE", lambda s: s.kind == "balance:EQUITIES"),
    ("Which mutual funds do I hold?", "HOLDINGS", lambda s: s.kind == "holding:MUTUAL_FUNDS"),
    ("What are my stock holdings?", "HOLDINGS", lambda s: s.kind == "holding:EQUITIES"),
    ("How much life insurance cover do I have?", "INSURANCE_COVER", lambda s: s.kind == "policy:TERM"),
    ("What is my health insurance cover?", "INSURANCE_COVER", lambda s: s.kind == "policy:HEALTH"),
    ("What is the total sum assured on my policies?", "INSURANCE_COVER", lambda s: s.kind.startswith("policy:")),
]

# Questions the service should decline: advice, forecasts, and data it does not hold.
OUT_OF_SCOPE = [
    "Should I buy more Infosys shares?",
    "Is now a good time to invest in mutual funds?",
    "What will the Sensex do next year?",
    "What is my credit score?",
    "How much do I owe on my credit card?",
    "What's the weather in Bengaluru?",
    "Write me a poem about money",
    "What is the repo rate?",
]

# Held-out phrasings, written after the first run and before the parser was changed in response to
# it. The dev set above was used to find and fix parser gaps; this set was not (though its misses
# were visible at the time), so its numbers are the fairer estimate for wording the parser has not seen.
HELD_OUT_FLOW = [
    ("What was my total spending {p}?", "SPEND_TOTAL", SPEND_KINDS, {}),
    ("How much have I spent on dining {p}?", "SPEND_CATEGORY", {"food"}, {}),
    ("Total spent on petrol {p}", "SPEND_CATEGORY", {"fuel"}, {}),
    ("What did I pay for rent {p}?", "SPEND_CATEGORY", {"rent"}, {}),
    ("How much went on utilities {p}?", "SPEND_CATEGORY", {"utilities"}, {}),
    ("How much did commuting cost me {p}?", "SPEND_CATEGORY", {"transport"}, {}),
    ("How much did I take out from ATMs {p}?", "SPEND_CATEGORY", {"cash"}, {}),
    ("How much was I paid in salary {p}?", "INCOME", {"salary"}, {}),
    ("What did I earn {p}?", "INCOME", {"salary"}, {}),
    ("How much did I put into mutual funds {p}?", "MF_INVESTED", {"mf_buy"}, {}),
    ("What were my EPF contributions {p}?", "EPF_CONTRIBUTIONS", {"epf_contribution"}, {}),
    ("What were my largest transactions {p}?", "TOP_EXPENSES", SPEND_KINDS, {"top": 5}),
    ("How much have I paid towards insurance {p}?", "PREMIUMS_PAID", {"premium"}, {}),
    ("Was my food spending higher {p} than the period before?", "SPEND_CATEGORY", {"food"}, {"compare": True}),
    ("How much of my salary did I save {p}?", "SAVINGS_RATE", SPEND_KINDS, {"savings": True}),
    ("Which payees took most of my money {p}?", "TOP_MERCHANTS", SPEND_KINDS, {"merchants": 5}),
    ("How much do I spend per month on average {p}?", "SPEND_TREND", SPEND_KINDS, {"trend": True}),
]

HELD_OUT_SNAPSHOT = [
    ("How much is in my provident fund?", "BALANCE", lambda s: s.kind == "balance:EPF"),
    ("What's my bank balance?", "BALANCE", lambda s: s.kind == "balance:DEPOSIT"),
    ("What is my total wealth?", "BALANCE", lambda s: s.kind.startswith("balance:")),
    ("List my mutual fund holdings", "HOLDINGS", lambda s: s.kind == "holding:MUTUAL_FUNDS"),
    ("How much term insurance cover do I have?", "INSURANCE_COVER", lambda s: s.kind == "policy:TERM"),
]

HELD_OUT_OOS = [
    "Should I increase my SIP?",
    "Which fund will give the best returns?",
    "Can I afford a car loan?",
    "How do I file my income tax return?",
    "What's my home loan EMI?",
    "Predict my spending next month",
]

SETS = {
    "dev": (FLOW_QUESTIONS, SNAPSHOT_QUESTIONS, OUT_OF_SCOPE),
    "held-out": (HELD_OUT_FLOW, HELD_OUT_SNAPSHOT, HELD_OUT_OOS),
}

# Asked under one provider's consent only, to check nothing outside that consent is used.
SCOPE_QUESTIONS = [
    "What is my net worth?",
    "How much did I spend last quarter?",
    "How much did I pay in insurance premiums in FY 2025-26?",
    "How much did I invest in mutual funds this financial year?",
    "How much was contributed to my EPF since June 2026?",
    "Which mutual funds do I hold?",
]


def expected_flow(user, kinds, frm, to, top=None, compare=False, extra=None):
    extra = extra or {}

    def pick(a, b):
        return [t for t in user.txns if t.kind in kinds and a <= t.day <= b]

    cur = pick(frm, to)
    exp = {}
    if extra.get("savings"):
        income = [t for t in user.txns if t.kind == "salary" and frm <= t.day <= to]
        earned = sum((t.amount for t in income), Decimal(0))
        spent = sum((t.amount for t in cur), Decimal(0))
        exp["figs"] = {"income": earned, "spending": spent, "saved": earned - spent}
        if earned > 0:
            exp["figs"]["savingsRate"] = ((earned - spent) * 100 / earned).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
        exp["total"] = None
        exp["cited"] = {t.record_id for t in income + cur}
        return exp
    if extra.get("merchants") or extra.get("trend"):
        groups = {}
        for t in cur:
            key = t.payee if extra.get("merchants") else (t.day.year, t.day.month)
            groups[key] = groups.get(key, Decimal(0)) + t.amount
        values = sorted(groups.values(), reverse=True)
        exp["total"] = sum((t.amount for t in cur), Decimal(0))
        exp["cited"] = {t.record_id for t in cur}
        if extra.get("merchants"):
            exp["values"] = values[: extra["merchants"]]
            exp["figs"] = {"merchants": Decimal(len(values))}
        else:
            exp["values"] = values
            if values:
                exp["figs"] = {"months": Decimal(len(values)),
                               "monthlyAverage": (exp["total"] / len(values)).quantize(PAISA, rounding=ROUND_HALF_UP)}
        return exp
    if top:
        ranked = sorted(cur, key=lambda t: (-t.amount, t.record_id))[:top]
        exp["total"] = sum((t.amount for t in ranked), Decimal(0))
        exp["items"] = [(t.record_id, t.amount) for t in ranked]
        exp["cited"] = {t.record_id for t in ranked}
        return exp
    exp["total"] = sum((t.amount for t in cur), Decimal(0))
    exp["cited"] = {t.record_id for t in cur}
    if compare:
        pf, pt = previous(frm, to)
        prev = pick(pf, pt)
        exp["previousTotal"] = sum((t.amount for t in prev), Decimal(0))
        exp["change"] = exp["total"] - exp["previousTotal"]
        exp["cited"] |= {t.record_id for t in prev}
    return exp


# ------------------------------------------------------------------------ scoring

AMOUNT = re.compile(r"₹\s?([\d,]+(?:\.\d+)?)")
PERCENT = re.compile(r"(-?[\d.]+)%")


def post(url, body):
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read()), time.perf_counter() - t0
    except urllib.error.HTTPError as e:
        return e.code, None, time.perf_counter() - t0


def figures(resp):
    return {f["key"]: Decimal(str(f["value"])) for f in resp.get("figures", [])}


def prose_ok(resp):
    """Every ₹ amount and % in the sentence must equal a number the service computed."""
    known = set(figures(resp).values())
    known |= {Decimal(str(b["value"])) for b in resp.get("breakdown", [])}
    known |= {Decimal(str(c["amount"])) for c in resp.get("citations", []) if c.get("amount") is not None}
    known = {abs(k).quantize(PAISA) for k in known}
    bad = []
    for m in AMOUNT.finditer(resp["answer"]):
        v = Decimal(m.group(1).replace(",", "")).quantize(PAISA)
        if v not in known:
            bad.append(m.group(0))
    pcts = {abs(v).quantize(Decimal("0.1")) for k, v in figures(resp).items() if k in ("changePct", "savingsRate")}
    for m in PERCENT.finditer(resp["answer"]):
        if abs(Decimal(m.group(1))).quantize(Decimal("0.1")) not in pcts:
            bad.append(m.group(0))
    return bad


def prf(cited, expected):
    if not cited and not expected:
        return 1.0, 1.0
    tp = len(cited & expected)
    return (tp / len(cited) if cited else 0.0), (tp / len(expected) if expected else 1.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8084")
    ap.add_argument("--ingestion", default="http://localhost:8081")
    ap.add_argument("--as-of", default="2026-10-25")
    ap.add_argument("--data", default=str(ROOT / "data" / "synthetic"))
    ap.add_argument("--out", default=str(ROOT / "evaluation" / "results"))
    args = ap.parse_args()
    as_of = date.fromisoformat(args.as_of)
    users = [load_user(p) for p in sorted(Path(args.data).glob("*.json"))]
    if not users:
        sys.exit(f"no synthetic data in {args.data}; run the data generator first")
    query = args.url.rstrip("/") + "/api/query"

    rows, latencies = [], []

    def ask(user, question, consent_ids=None):
        body = {"question": question}
        if consent_ids:
            body["consentIds"] = consent_ids
        else:
            body["customerId"] = user.customer_id
        status, resp, secs = post(query, body)
        latencies.append(secs)
        return status, resp

    # --- answerable questions
    for qset, (flow_qs, snapshot_qs, oos_qs) in SETS.items():
        for user in users:
            run_user(user, qset, flow_qs, snapshot_qs, oos_qs, as_of, ask, rows)

    # --- consent scoping: each provider's seeded consent on its own
    scope_rows = []
    for user in users:
        short = user.customer_id.split("@")[0]
        for fip in sorted(user.fips):
            cid = f"CN-{short}-{fip.removeprefix('ETHERM-').removesuffix('-FIP')}"
            for q in SCOPE_QUESTIONS:
                status, resp = ask(user, q, [cid])
                leaked = [] if resp is None else [c["recordId"] for c in resp["citations"] if c["fipId"] != fip]
                scope_rows.append({"user": user.customer_id, "consent": cid, "question": q, "status": status,
                                   "leaked": leaked})

    # --- consent lifecycle: a fresh consent answers, then is refused once revoked
    lifecycle = []
    for user in users[:2]:
        status, created = post(args.ingestion + "/api/consents", {
            "customerId": user.customer_id, "fiTypes": ["DEPOSIT"], "dataFrom": "2025-10-01",
            "dataTo": "2026-09-30", "validForDays": 30})[:2]
        cid = created["consentId"]
        post(f"{args.ingestion}/api/consents/{cid}/approve", {})
        before, _ = ask(user, "How much did I spend last quarter?", [cid])
        post(f"{args.ingestion}/api/consents/{cid}/revoke", {})
        after, _ = ask(user, "How much did I spend last quarter?", [cid])
        lifecycle.append({"user": user.customer_id, "consent": cid, "status_before": before, "status_after": after,
                          "ok": before == 200 and after == 403})

    report = summarise(rows, scope_rows, lifecycle, latencies, args, users)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "results.json").write_text(json.dumps({"rows": rows, "scope": scope_rows, "lifecycle": lifecycle},
                                                 indent=1, default=str))
    (out / "REPORT.md").write_text(report)
    print(report)


def run_user(user, qset, flow_qs, snapshot_qs, oos_qs, as_of, ask, rows):
    for phrase, (frm, to) in periods(as_of).items():
        for template, intent, kinds, extra in flow_qs:
            q = template.format(p=phrase)
            exp = expected_flow(user, kinds, frm, to, extra.get("top"), extra.get("compare", False), extra)
            status, resp = ask(user, q)
            rows.append(score(user, q, "flow", intent, (frm, to), exp, status, resp) | {"set": qset})
    for q, intent, select in snapshot_qs:
        chosen = [s for s in user.snapshots if select(s)]
        exp = {"total": sum((s.value for s in chosen), Decimal(0)), "cited": {s.record_id for s in chosen}}
        status, resp = ask(user, q)
        rows.append(score(user, q, "snapshot", intent, None, exp, status, resp) | {"set": qset})
    for q in oos_qs:
        status, resp = ask(user, q)
        refused = status == 200 and resp["intent"] == "UNSUPPORTED" and not resp["citations"]
        rows.append({"user": user.customer_id, "question": q, "group": "out_of_scope", "set": qset, "status": status,
                     "intent_ok": refused, "refused": refused,
                     "got_intent": resp and resp["intent"], "answer": resp and resp["answer"]})


def score(user, q, group, intent, period, exp, status, resp):
    row = {"user": user.customer_id, "question": q, "group": group, "intent": intent, "status": status}
    if status != 200:
        row.update(intent_ok=False, period_ok=False, exact=False, precision=0.0, recall=0.0, prose_bad=[])
        return row
    row["got_intent"] = resp["intent"]
    row["answer"] = resp["answer"]
    row["intent_ok"] = resp["intent"] == intent
    if period:
        got = resp.get("period") or {}
        row["period_ok"] = (got.get("from"), got.get("to")) == (period[0].isoformat(), period[1].isoformat())
    figs = figures(resp)
    checks = [] if exp["total"] is None else [figs.get("total") == exp["total"]]
    for k, v in exp.get("figs", {}).items():
        checks.append(figs.get(k) == v)
    if "values" in exp:
        checks.append([Decimal(str(b["value"])) for b in resp.get("breakdown", [])] == exp["values"])
    for k in ("previousTotal", "change"):
        if k in exp:
            checks.append(figs.get(k) == exp[k])
    if "items" in exp:
        got_items = [(b["recordIds"][0], Decimal(str(b["value"]))) for b in resp.get("breakdown", [])]
        checks.append(got_items == exp["items"])
    row["exact"] = row["intent_ok"] and all(checks)
    row["expected_total"] = str(exp["total"])
    row["got_total"] = str(figs.get("total"))
    cited = {c["recordId"] for c in resp["citations"]}
    row["precision"], row["recall"] = prf(cited, exp["cited"])
    row["prose_bad"] = prose_ok(resp)
    return row


def pct(n, d):
    return f"{100 * n / d:.1f}%" if d else "n/a"


def metrics(rows):
    answerable = [r for r in rows if r["group"] in ("flow", "snapshot")]
    flows = [r for r in answerable if r["group"] == "flow"]
    oos = [r for r in rows if r["group"] == "out_of_scope"]
    parsed = [r for r in answerable if r["intent_ok"] and r.get("period_ok", True)]
    return [
        ("Answerable questions", str(len(answerable))),
        ("Intent accuracy", pct(sum(r["intent_ok"] for r in answerable), len(answerable))),
        ("Period accuracy (time-bound questions)", pct(sum(r.get("period_ok", False) for r in flows), len(flows))),
        ("Exact-match answers (to the paisa)", pct(sum(r["exact"] for r in answerable), len(answerable))),
        ("Exact match when intent and period were parsed right",
         f"{pct(sum(r['exact'] for r in parsed), len(parsed))} of {len(parsed)}"),
        ("Citation precision (mean)", f"{statistics.mean(r['precision'] for r in answerable):.3f}"),
        ("Citation recall (mean)", f"{statistics.mean(r['recall'] for r in answerable):.3f}"),
        ("Sentences with a number not among the computed figures",
         f"{sum(bool(r['prose_bad']) for r in answerable)} of {len(answerable)}"),
        ("Out-of-scope questions declined", f"{pct(sum(r['refused'] for r in oos), len(oos))} of {len(oos)}"),
    ]


PERIOD_PHRASES = re.compile(r"\b(last month|last quarter|in the last 6 months|in March 2026|between January and "
                            r"March 2026|in FY 2025-26|this financial year|since June 2026)\b")


def summarise(rows, scope_rows, lifecycle, latencies, args, users):
    by_set = {name: [r for r in rows if r["set"] == name] for name in SETS}
    table = {name: metrics(rs) for name, rs in by_set.items()}
    lines = [
        "# Query accuracy evaluation",
        "",
        f"Generated by `evaluation/evaluate.py` against `{args.url}`, as of {args.as_of}, over "
        f"{len(users)} synthetic users. Ground truth is computed in Python from the raw FI JSON, "
        "independently of the Java normalisers and compute engine. Each time-bound question is asked "
        f"for {len(periods(date.fromisoformat(args.as_of)))} periods for every user.",
        "",
        "The dev set was used to find and fix parser gaps; the held-out set was written before those "
        "fixes, so it is the fairer estimate for unseen wording.",
        "",
        "Read these numbers with their limits. The questions come from "
        f"{sum(len(f) + len(sn) for f, sn, _ in SETS.values())} answerable phrasings and "
        f"{sum(len(o) for _, _, o in SETS.values())} out-of-scope ones, multiplied across periods and users, so "
        "wording diversity is far smaller than the question count. The parser is rule-based and was written "
        "by the same people who wrote the questions. The held-out misses were visible when the dev fixes "
        "were made, and one general rule (insurance plus a cost word means premiums) fixed a held-out miss "
        "as well. See BASELINE.md for the run before the fixes.",
        "",
        "## Summary",
        "",
        "| Metric | " + " | ".join(SETS) + " |",
        "| --- | " + " | ".join("---" for _ in SETS) + " |",
    ]
    for k, (label, _) in enumerate(next(iter(table.values()))):
        lines.append(f"| {label} | " + " | ".join(table[name][k][1] for name in SETS) + " |")
    lines += [
        "",
        "| Consent and runtime checks | Result |",
        "| --- | --- |",
        f"| Single-consent answers citing another provider | "
        f"{sum(bool(r['leaked']) for r in scope_rows)} of {len(scope_rows)} |",
        f"| Requests under a single consent that failed | "
        f"{sum(r['status'] != 200 for r in scope_rows)} of {len(scope_rows)} |",
        f"| Revoked consent refused with 403 | {sum(r['ok'] for r in lifecycle)} of {len(lifecycle)} |",
        f"| Latency p50 / p95 (local) | {statistics.median(latencies) * 1000:.0f} ms / "
        f"{sorted(latencies)[int(len(latencies) * 0.95)] * 1000:.0f} ms over {len(latencies)} requests |",
        "",
        "## By expected intent (both sets)",
        "",
        "| Intent | Questions | Intent right | Exact match |",
        "| --- | --- | --- | --- |",
    ]
    answerable = [r for r in rows if r["group"] in ("flow", "snapshot")]
    by = defaultdict(list)
    for r in answerable:
        by[r["intent"]].append(r)
    for k in sorted(by):
        rs = by[k]
        lines.append(f"| {k} | {len(rs)} | {pct(sum(r['intent_ok'] for r in rs), len(rs))} | "
                     f"{pct(sum(r['exact'] for r in rs), len(rs))} |")

    # Misses grouped by question template, so one parser gap shows once rather than forty times.
    fails = defaultdict(list)
    for r in answerable:
        if not r["exact"] or r.get("period_ok") is False:
            fails[(r["set"], PERIOD_PHRASES.sub("{p}", r["question"]))].append(r)
    lines += ["", "## Misses", ""]
    if not fails:
        lines.append("None.")
    else:
        lines += ["| Set | Question | Misses | Expected | Got | Example |", "| --- | --- | --- | --- | --- | --- |"]
        for (qset, key), rs in sorted(fails.items(), key=lambda kv: (kv[0][0], -len(kv[1]))):
            ex = rs[0]
            why = (f"period {'wrong' if ex.get('period_ok') is False else 'ok'}; "
                   f"expected {ex.get('expected_total')}, got {ex.get('got_total')}")
            lines.append(f"| {qset} | {key} | {len(rs)} | {ex['intent']} | {ex.get('got_intent')} | {why} |")
    wrong_oos = [r for r in rows if r["group"] == "out_of_scope" and not r["refused"]]
    lines += ["", "## Out-of-scope questions that were answered", ""]
    if not wrong_oos:
        lines.append("None.")
    for q in sorted({r["question"] for r in wrong_oos}):
        r = next(x for x in wrong_oos if x["question"] == q)
        lines.append(f"- ({r['set']}) \"{q}\" was read as {r['got_intent']}: \"{r['answer']}\"")
    lines.append("")
    return "\n".join(lines)


if __name__ == "__main__":
    main()
