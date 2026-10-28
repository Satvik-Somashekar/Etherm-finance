# Security

Etherm is an academic prototype that runs on synthetic data only. It holds no real customer data and has
no production deployment.

## Reporting a problem

Please do not open a public issue for a security problem. Use GitHub's private vulnerability reporting
(Security tab → "Report a vulnerability") with the steps to reproduce it. You will get a reply within a week.

## What is in place

| Area | Control | Where |
| --- | --- | --- |
| Prompt injection | Payee names cleaned and withheld when they read like instructions; replies refused if they carry their own numbers, amounts in words, links or markup; earlier turns signed | `UntrustedText`, `ChatService`, `TurnSigner`, `RedTeamTest`, `evaluation/redteam.py` |
| Credentials | Keys only in environment variables; a scanner checks every file, every commit (pre-commit hook) and the whole history (CI) | `scripts/secret-scan.py`, `.githooks/pre-commit` |
| Dependencies | Exact versions; lockfile installs with integrity hashes; Maven enforcer bans unpinned plugins, diverging versions and known-bad libraries; `npm audit` on every PR; Dependabot weekly | `pom.xml`, `frontend/.npmrc`, `.github/` |
| Build | CI actions pinned to commit SHAs with read-only permissions; base image pinned by digest; CycloneDX bills of materials for backend and frontend | `.github/workflows/ci.yml`, `Dockerfile` |
| Access | Signed, expiring tokens from a passcode sign-in (PBKDF2, lockout, rate-limited); every endpoint checks the token and serves only that customer's data and consents; service-only endpoints; per-customer rate limits; body and question size limits; security headers | `security/`, `SessionController`, `QueryController` |
| Containers | Non-root user, read-only filesystem, no capabilities, no privilege escalation, ports bound to localhost | `Dockerfile`, `docker-compose.yml` |

## Not in place yet

Every demo customer shares one passcode, so there is no real identity behind a sign-in; tokens cannot be
revoked before they expire; services talk over plain HTTP. Data is held in memory, not encrypted at rest.
See the security strategy for the roadmap.
