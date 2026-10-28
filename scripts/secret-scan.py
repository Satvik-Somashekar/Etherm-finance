#!/usr/bin/env python3
"""Looks for credentials in the repository before they reach GitHub.

Checks every tracked file (default), the files staged for commit (--staged, used by the pre-commit hook), or
every version of every file in the history (--history). Exits 1 and prints file:line for each finding; the
secret itself is never printed, only its kind and the first four characters.

A line can be allowed by ending it with the comment `secret-scan: allow` after checking it is not a secret
(for example a documented placeholder). Standard library only.

Usage: python3 scripts/secret-scan.py [--staged | --history]
"""

import argparse
import math
import re
import subprocess
import sys

RULES = [
    ("Anthropic API key", re.compile(r"sk-ant-[A-Za-z0-9_-]{20,}")),
    ("Google API key", re.compile(r"AIza[0-9A-Za-z_-]{35}")),
    ("OpenAI API key", re.compile(r"sk-(?:proj-)?[A-Za-z0-9]{32,}")),
    ("GitHub token", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,})\b")),
    ("AWS access key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("Slack token", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b")),
    ("Stripe key", re.compile(r"\b[sr]k_live_[A-Za-z0-9]{20,}\b")),
    ("Private key", re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY-----")),
    ("JSON web token", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")),
    ("Database URL with password", re.compile(r"\b(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis)://[^:\s/]+:[^@\s]{4,}@")),
]
# A value assigned to something called a key, secret, token or password, long and random enough to be real.
ASSIGNED = re.compile(r"""(?i)\b[\w.-]*(?:api[_-]?key|secret|token|passw(?:or)?d|credential)[\w.-]*\s*[:=]\s*["']([^"'\s]{16,})["']""")
PLACEHOLDER = re.compile(r"(?i)(?:example|placeholder|changeme|your[_-]|xxxx|\$\{|<[^>]+>|dummy|test|fake|redacted)")
ALLOW = "secret-scan: allow"
SKIP = re.compile(r"(?:^|/)(?:package-lock\.json|.*\.(?:png|jpe?g|gif|ico|pdf|woff2?|ttf|jar|zip|gz))$")


def entropy(s):
    counts = {c: s.count(c) for c in set(s)}
    return -sum(n / len(s) * math.log2(n / len(s)) for n in counts.values())


def scan_text(name, text):
    findings = []
    for number, line in enumerate(text.splitlines(), 1):
        if ALLOW in line:
            continue
        for kind, rule in RULES:
            m = rule.search(line)
            if m:
                findings.append((name, number, kind, m.group(0)[:4]))
        m = ASSIGNED.search(line)
        if m and not PLACEHOLDER.search(m.group(1)) and entropy(m.group(1)) > 3.5:
            findings.append((name, number, "high-entropy value assigned to a secret-like name", m.group(1)[:4]))
    return findings


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, check=True).stdout


def main():
    ap = argparse.ArgumentParser()
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--staged", action="store_true", help="scan what is staged for commit")
    mode.add_argument("--history", action="store_true", help="scan every version of every file in the history")
    args = ap.parse_args()

    findings = []
    if args.history:
        seen = set()
        for line in git("rev-list", "--all", "--objects").decode().splitlines():
            sha, _, path = line.partition(" ")
            if not path or sha in seen or SKIP.search(path):
                continue
            seen.add(sha)
            if git("cat-file", "-t", sha).strip() != b"blob":
                continue
            findings += scan_text(f"{path}@{sha[:8]}", git("cat-file", "-p", sha).decode("utf-8", "replace"))
    else:
        listing = git("diff", "--cached", "--name-only", "--diff-filter=ACMR") if args.staged else git("ls-files")
        for path in listing.decode().splitlines():
            if SKIP.search(path):
                continue
            blob = git("show", f":{path}") if args.staged else open(path, "rb").read()
            findings += scan_text(path, blob.decode("utf-8", "replace"))

    for name, number, kind, prefix in findings:
        print(f"{name}:{number}: {kind} ({prefix}...)")
    if findings:
        print(f"\n{len(findings)} possible secret(s). Move them to environment variables; if a real key was ever "
              "committed, revoke it first, because removing it from the files does not remove it from the history.")
        sys.exit(1)
    print("No secrets found.")


if __name__ == "__main__":
    main()
