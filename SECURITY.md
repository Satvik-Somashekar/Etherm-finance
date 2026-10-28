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
| Dependencies | Exact versions; lockfile installs with integrity hashes; Maven enforcer bans unpinned plugins, diverging versions and known-bad libraries; `npm audit` and dependency review on every PR; Dependabot weekly | `pom.xml`, `frontend/.npmrc`, `.github/` |
| Build | CI actions pinned to commit SHAs with read-only permissions; base image pinned by digest; CycloneDX bills of materials for backend and frontend | `.github/workflows/ci.yml`, `Dockerfile` |
| Containers | Non-root user, read-only filesystem, no capabilities, no privilege escalation, ports bound to localhost | `Dockerfile`, `docker-compose.yml` |

## Not in place yet

Sign-in is a demo picker and the services accept any customer id, so there is no access control yet. Data
is held in memory, not encrypted at rest. See the security strategy for the roadmap.
