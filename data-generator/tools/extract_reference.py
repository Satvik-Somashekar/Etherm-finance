#!/usr/bin/env python3
"""Extracts the small set of real public prices the data generator uses.

Inputs (downloaded by hand, not committed):
  AMFI NAV report, one file per date, from https://www.amfiindia.com/net-asset-value/nav-history
  NSE CM-UDiFF Common Bhavcopy Final CSVs, from https://www.nseindia.com/all-reports

Usage: extract_reference.py <amfi-dir> <nse-dir> <out-dir>
  <amfi-dir> holds *.txt files named by the generator's date map below; <nse-dir> holds the bhavcopy csvs.

Only a handful of funds and stocks, on about one date a month, are written. The output is a few
hundred rows of price facts; the full source files are far larger and stay out of the repository.
"""

import csv
import glob
import os
import re
import sys

FUNDS = [  # AMFI ISIN (direct plan, growth) of the funds the synthetic customers hold
    "INF179K01UT0",  # HDFC Flexi Cap Fund
    "INF846K01EH3",  # Axis Midcap Fund
    "INF846K013D2",  # Axis Nifty 50 Index Fund
    "INF846K01DP8",  # Axis Large Cap Fund
    "INF179K01WA6",  # HDFC Balanced Advantage Fund
    "INF179KB1HP9",  # HDFC Liquid Fund
]
STOCKS = ["INFY", "TCS", "HDFCBANK", "RELIANCE", "ITC", "ICICIBANK", "SBIN", "LT"]
WINDOW = ("2025-10-01", "2026-10-01")  # one price point at or after the data window's end


def fund_rows(amfi_dir):
    nav, meta = [], {}
    for path in sorted(glob.glob(os.path.join(amfi_dir, "*.txt"))):
        section = amc = None
        for line in open(path, encoding="utf-8", errors="replace").read().split("\n"):
            line = line.rstrip("\r")
            p = line.split(";")
            if len(p) == 8 and p[0].isdigit():
                isin = p[4].strip()
                if isin in FUNDS and p[2] == "Direct Plan":
                    day = __import__("datetime").datetime.strptime(p[7], "%d-%b-%Y").date().isoformat()
                    nav.append((isin, day, p[6]))
                    meta[isin] = (p[0], amc, p[1], re.sub(r"^Open Ended Schemes \( (.*) \)$", r"\1", section or ""))
            elif line.startswith(("Open Ended", "Close Ended", "Interval")):
                section = line.strip()
            elif line.strip().endswith("Mutual Fund"):
                amc = line.strip()
    return sorted(set(nav)), meta


def stock_rows(nse_dir):
    closes, meta = [], {}
    for path in sorted(glob.glob(os.path.join(nse_dir, "*.csv"))):
        for r in csv.DictReader(open(path, encoding="utf-8", errors="replace")):
            if r["SctySrs"] == "EQ" and r["TckrSymb"] in STOCKS:
                closes.append((r["TckrSymb"], r["TradDt"], r["ClsPric"]))
                meta[r["TckrSymb"]] = (r["ISIN"], r["FinInstrmNm"])
    return sorted(set(closes)), meta


def main(amfi_dir, nse_dir, out):
    nav, fmeta = fund_rows(amfi_dir)
    closes, smeta = stock_rows(nse_dir)
    nav = [r for r in nav if WINDOW[0] <= r[1] <= WINDOW[1]]
    closes = [r for r in closes if WINDOW[0] <= r[1] <= WINDOW[1]]
    os.makedirs(out, exist_ok=True)
    for name, header, rows in [
        ("funds.csv", ["isin", "amfi_code", "amc", "scheme", "category"],
         [(i, *fmeta[i]) for i in FUNDS]),
        ("fund_nav.csv", ["isin", "date", "nav"], nav),
        ("stocks.csv", ["symbol", "isin", "name"], [(s, *smeta[s]) for s in STOCKS]),
        ("stock_close.csv", ["symbol", "date", "close"], closes),
    ]:
        with open(os.path.join(out, name), "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(header)
            w.writerows(rows)
        print(name, len(rows))


if __name__ == "__main__":
    main(*sys.argv[1:4])
