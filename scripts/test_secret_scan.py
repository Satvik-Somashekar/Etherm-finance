"""Checks scripts/secret-scan.py finds what it should. Fake secrets are built at run time so this file has none.

Usage: python3 scripts/test_secret_scan.py
"""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("scan", Path(__file__).with_name("secret-scan.py"))
scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scan)

R = "Q7vX2mLp9KzR4tWb8NcY1hJd6FgS3aE5"
should_find = {
    "Anthropic API key": "ANTHROPIC_API_KEY=" + "sk-ant-" + "api03-" + R,
    "Google API key": "key = " + "AI" + "za" + R + "abc",
    "GitHub token": "token: " + "gh" + "p_" + R + "abcd",
    "AWS access key": "AK" + "IA" + "ABCDEFGHIJKLMNOP",
    "Private key": "-----BEGIN " + "RSA PRIVATE KEY-----",
    "Database URL with password": "postgres" + "://etherm:" + "s3cretPass" + "@db:5432/etherm",
    "high-entropy value assigned to a secret-like name": 'client_secret = "' + R + '"',
}
should_pass = [
    'GEMINI_API_KEY: ${GEMINI_API_KEY:-}',
    'apiKey = "your-api-key-here"',
    'password = "changeme-in-production"',
    'token = "' + R + '"  # secret-scan: allow',
    'The key is read from the environment by the caller.',
]
failures = 0
for kind, line in should_find.items():
    kinds = [f[2] for f in scan.scan_text("t", line)]
    if kind not in kinds:
        print(f"MISSED {kind}: got {kinds}")
        failures += 1
for line in should_pass:
    found = scan.scan_text("t", line)
    if found:
        print(f"FALSE POSITIVE on {line!r}: {found}")
        failures += 1
print("ok" if not failures else f"{failures} failure(s)")
raise SystemExit(1 if failures else 0)
