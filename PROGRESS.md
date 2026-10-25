# Progress

**As of 2 September 2026.** Measured against the five architecture components and
four technical challenges in [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md).

> **Overall: roughly 20% complete.**
>
> One of the five architecture components is essentially finished (the query
> interface). The other four — mock data generation, aggregation/normalization,
> retrieval, and the RAG query service — are still at Day-1 scaffolding. The
> project currently has a complete front end sitting on top of an empty backend.

Last commit is `07d07b7 Scaffold Spring Boot service modules`. **Everything below
that is marked done is uncommitted** — `frontend/`, `docs/` and `.claude/` are
untracked, and `README.md` / `.gitignore` are modified.

---

## Architecture components

| # | Component | Status | Done |
| --- | --- | --- | --- |
| 1 | Mock data generator | 🟡 Partial | ~25% |
| 2 | Aggregation / normalization service | 🔴 Scaffolding only | 0% |
| 3 | Retrieval layer | 🔴 Scaffolding only | 0% |
| 4 | RAG query service | 🔴 Scaffolding only | 0% |
| 5 | Query interface (conversational front end) | 🟢 Built | ~85% |

**1 — Mock data generator (~25%).** A realistic, schema-shaped dataset exists at
`frontend/src/data/mock.ts` (333 lines): 9 accounts across 5 FI types, consent
artefacts, transactions, a 12-month net-worth series, a FIP catalogue, and a
pre-computed premium breakdown. What is missing is the actual *generator* — this
is hand-written TypeScript on the client, not a Java component emitting AA-format
JSON against Sahamati's FI schemas. The shapes are right; the machinery is not
built.

**2, 3, 4 — Backend (0%).** All four Maven modules are unchanged from the initial
scaffold: one 12-line `@SpringBootApplication` class each, plus an
`application.yml` exposing Actuator health. No controllers, no domain model, no
persistence, no embeddings, no inter-service calls.

**5 — Query interface (~85%).** Four screens, fully built and running. Deduction
is for the missing backend wiring, not missing UI.

## Technical challenges

| # | Challenge | Status | Notes |
| --- | --- | --- | --- |
| 1 | Numerical grounding / retrieve-then-compute | 🟡 ~20% | Pattern demonstrated in the UI: every aggregate (net worth, allocation shares, premium totals) is summed in code from records, never hardcoded or model-generated. No server-side implementation. |
| 2 | Schema diversity / flexible normalization | 🟡 ~15% | Five FI types modelled in `types/fi.ts` with a shared `FipAccount` shape. No parsers, no normalization layer. |
| 3 | Consent simulation | 🟡 ~40% | `ConsentArtifact` models purpose code, fetch type, frequency, validity window and FI types. Lifecycle works end-to-end in the UI (`PENDING → ACTIVE`, expiry countdown, revocation copy). Nothing enforces it server-side. |
| 4 | Source traceability | 🟡 ~30% | Oracle answers cite the record IDs they were computed from, and dashboard insights show a grounded-in-N-records line. The citations are currently static rather than produced by a real retrieval pass. |

---

## What is actually built

### Frontend — `frontend/`, ~2,325 lines of TypeScript

Vite 8 + React 19 + TypeScript + Tailwind 3.4 + react-router 7. Builds clean
(`npm run build`, 40 modules). No console errors.

**Design system.** Etherm Lumina, ported from the Stitch export in
`docs/design/DESIGN.md` into `tailwind.config.ts` — full M3 colour token set,
Inter type scale, the DESIGN.md radius scale, and glassmorphic surface classes
(`.glass`, `.glass-floating`) implementing Level 1 and floating elevation.

**Screens.**

| Route | File | State |
| --- | --- | --- |
| `/` | `pages/Dashboard.tsx` | Net worth, working timeframe pills, allocation by FI type, recent activity, grounded insights |
| `/accounts` | `pages/Accounts.tsx` | FIP cards, working FI-type filter, full connect-account flow |
| `/oracle` | `pages/AiOracle.tsx` | Chat with metric cards, bar chart and source citations; composer returns a stub |
| `/consent` | `pages/Consent.tsx` | Consent posture, sessions, policy panels, consent ledger table |

**Working interactions.** Timeframe range selection with recomputed change
percentage; FI-type filtering; Privacy Mode masking every rupee figure app-wide
via context; the two-step AA connect flow (FIP picker → consent review →
approve → simulated sync → active account with balance), reachable both from
"Link via AA" and from a pending account's "Approve in AA app".

**Verified.** Desktop (1440px) and mobile (375px), no horizontal overflow, clean
console, production build passing.

### Docs

`docs/design/` holds DESIGN.md, the four original Stitch renders, and a mapping
from each render to its implemented page. `README.md` documents the frontend,
its routes, and the data strategy.

---

## What is not built

- **Every backend endpoint.** No `POST /query`, no FI fetch, no normalization,
  no embeddings, no vector store. The Oracle composer has a stub where the
  `rag-service` call will go.
- **Any Java beyond four main classes.** No domain model, no DTOs, no
  repositories, no configuration past Actuator.
- **Persistence.** No database, no schema, no migrations.
- **The RAG layer itself.** The existing Financial RAG model mentioned in
  PROJECT_CONTEXT.md has not been brought into this repo.
- **Tests.** Zero, on both sides — no JUnit, no Vitest.
- **Docker / CI.** No Dockerfile, no pipeline.
- **Live AA integration.** Correctly out of scope for this phase.

## Known issues

- **Frontend data is client-side only.** Linked accounts persist to
  localStorage, but everything else (Oracle transcript, Privacy Mode) resets on
  reload, and nothing is backed by a server.

## Suggested next steps

1. Commit what exists — the frontend is a substantial body of untracked work.
2. Define the API contract the frontend already implies: account list, aggregate
   balance, transactions, and `POST /query` returning an answer plus source
   record IDs.
3. Build `ingestion` + `normalization` against the same FI shapes as
   `types/fi.ts`, with a real Java-side mock data generator behind them.
4. Replace `data/mock.ts` with a typed API client — the type layer already
   matches, so this should be a data-source swap rather than a UI rewrite.
5. Wire `retrieval` and `rag-service`, keeping aggregates computed in code and
   returning the record IDs the UI already renders as citations.
