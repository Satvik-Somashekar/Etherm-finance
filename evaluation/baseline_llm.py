#!/usr/bin/env python3
"""Plain-LLM baseline: the same questions, but the model gets the raw records and no tools.

It answers numeric questions straight from the customer's raw FI JSON (the same files Etherm's
ground truth is computed from), with no Java compute layer, no consent bounding and no citations
check. Its final number is compared with the ground truth from evaluate.py. This tests the claim
that taking the model out of the arithmetic makes the answers correct.

Only questions with a single expected total are used (no comparisons, top-N, trends, savings
rate), so scoring is unambiguous. Questions come from evaluate.py's dev and held-out sets.

Usage:
  export ANTHROPIC_API_KEY=...            # your own key, from the environment only
  python3 evaluation/baseline_llm.py --dry-run          # counts and prompt size, no API calls
  python3 evaluation/baseline_llm.py --users 1 --limit 20   # small trial first
  python3 evaluation/baseline_llm.py                    # full run

The raw data for a user goes in a cached system block, so each extra question is cheap.
Requires: pip install anthropic
"""

import argparse
import json
import os
import re
import sys
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import evaluate as ev  # noqa: E402

ROOT = ev.ROOT
DEFAULT_MODEL = os.environ.get("ETHERM_BASELINE_MODEL", "claude-opus-5-5")

SYSTEM = (
    "You answer questions about one customer's finances using only the raw Account Aggregator "
    "records below. Work out the answer yourself from the records. Amounts are in Indian rupees. "
    "Today's date is {today}. Give a short explanation, then finish with a final line exactly of "
    "the form `ANSWER: <number>` with the single number that answers the question (plain digits, "
    "optional decimals, no commas or symbols). If the records cannot answer it, finish with "
    "`ANSWER: none`.\n\nRECORDS (JSON):\n{records}"
)

ANSWER = re.compile(r"ANSWER:\s*([-\d.,]+|none)", re.IGNORECASE)


def parse_answer(text):
    """The number after the last ANSWER: line, or None (declined or unparseable)."""
    found = ANSWER.findall(text or "")
    if not found or found[-1].lower() == "none":
        return None
    try:
        return Decimal(found[-1].replace(",", "").rstrip("."))
    except InvalidOperation:
        return None


def single_total_questions(as_of, users):
    """(set, user, question, expected total) for every question with one unambiguous total."""
    out = []
    for qset, (flow_qs, snapshot_qs, _oos) in ev.SETS.items():
        for user in users:
            for phrase, (frm, to) in ev.periods(as_of).items():
                for template, _intent, kinds, extra in flow_qs:
                    if extra:
                        continue
                    exp = ev.expected_flow(user, kinds, frm, to)
                    out.append((qset, user, template.format(p=phrase), exp["total"]))
            for q, intent, select in snapshot_qs:
                if intent == "HOLDINGS":
                    continue
                chosen = [s for s in user.snapshots if select(s)]
                out.append((qset, user, q, sum((s.value for s in chosen), Decimal(0))))
    return out


def grade(expected, got):
    if got is None:
        return "declined"
    if got == expected:
        return "exact"
    if abs(got - expected) <= Decimal("1"):
        return "rounded"
    return "wrong"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--as-of", default="2026-10-25")
    ap.add_argument("--data", default=str(ROOT / "data" / "synthetic"))
    ap.add_argument("--out", default=str(ROOT / "evaluation" / "results"))
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--users", type=int, default=0, help="only the first N users (0 = all)")
    ap.add_argument("--limit", type=int, default=0, help="at most N questions per user (0 = all)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    as_of = date.fromisoformat(args.as_of)
    paths = sorted(Path(args.data).glob("*.json"))
    if args.users:
        paths = paths[: args.users]
    users = [ev.load_user(p) for p in paths]
    raw = {u.customer_id: p.read_text() for u, p in zip(users, paths)}
    questions = single_total_questions(as_of, users)

    per_user = {}
    for qset, user, q, exp in questions:
        per_user.setdefault(user.customer_id, []).append((qset, user, q, exp))
    if args.limit:
        per_user = {k: v[:: max(1, len(v) // args.limit)][: args.limit] for k, v in per_user.items()}

    total = sum(len(v) for v in per_user.values())
    print(f"{len(users)} users, {total} questions, model {args.model}")
    for uid, text in raw.items():
        print(f"  {uid}: {len(text):,} chars of raw records (about {len(text) // 3:,} tokens)")
    if args.dry_run:
        return
    if not os.environ.get("ANTHROPIC_API_KEY"):
        sys.exit("ANTHROPIC_API_KEY is not set in the environment")

    import anthropic
    client = anthropic.Anthropic()
    rows = []
    for uid, items in per_user.items():
        system = [{"type": "text", "text": SYSTEM.format(today=as_of.isoformat(), records=raw[uid]),
                   "cache_control": {"type": "ephemeral"}}]
        for qset, user, q, exp in items:
            msg = client.messages.create(model=args.model, max_tokens=2000, system=system,
                                         messages=[{"role": "user", "content": q}])
            text = "".join(b.text for b in msg.content if b.type == "text")
            got = parse_answer(text)
            rows.append({"set": qset, "user": uid, "question": q, "expected": str(exp),
                         "got": None if got is None else str(got), "grade": grade(exp, got),
                         "answer": text[-400:]})
            print(f"{rows[-1]['grade']:9} {uid} {q}", flush=True)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "baseline_llm.json").write_text(json.dumps(rows, indent=1))
    (out / "BASELINE_LLM.md").write_text(report(rows, args))
    print((out / "BASELINE_LLM.md").read_text())


def report(rows, args):
    n = len(rows)

    def pct(k):
        c = sum(r["grade"] == k for r in rows)
        return f"{c} / {n} ({100 * c / n:.1f}%)"

    lines = [
        "# Plain-LLM baseline",
        "",
        f"Model `{args.model}`, raw records in context, no tools, no consent bounding. "
        f"{n} single-total questions over {len({r['user'] for r in rows})} customers, as of {args.as_of}.",
        "",
        "| Outcome | Count |", "|---|---|",
        f"| Exact to the paisa | {pct('exact')} |",
        f"| Within Rs 1 (rounded) | {pct('rounded')} |",
        f"| Wrong | {pct('wrong')} |",
        f"| Declined or unparseable | {pct('declined')} |",
        "",
        "Etherm on the same questions is exact on every one (see REPORT.md).",
        "",
        "Limits: the baseline is told nothing about Etherm's definitions (for example that SIP and "
        "share-purchase debits are not spending), so some misses are definition differences, not "
        "arithmetic. Read `baseline_llm.json` to separate the two before quoting a number.",
    ]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    main()
