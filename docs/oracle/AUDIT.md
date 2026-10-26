# Oracle Phase 0 audit

State of the repo on branch `feat/oracle-contracts` (stacked on the demo-mode PR). Describes what is built and tested; gaps are listed honestly.

## Services

| Module | Does today | Gap for the Oracle plan |
|---|---|---|
| `fi-core` | Models, normalisers for 5 FI types, consent scoping, `QueryEngine` (BigDecimal, record-level citations), `NetWorthHistory`, `PriceBook` | One fixed query shape (`QueryPlan` + 14 intents). No grouping/compare/rank generality, no rules, no projection, no goals. |
| `data-generator` | Synthetic AA-shaped data for 3 users, real AMFI NAVs / NSE closes / HCES shares | No fixed deposits, no purchase lots, no instrument reference (sector). |
| `ingestion` (8081) | Serves FIP payloads | Accepts any customer id (no auth). |
| `normalization` (8082) | Raw JSON to `FinancialRecord` | FD and lots not represented. |
| `retrieval` (8083) | Consent-bounded record fetch | Structured only, no embeddings (cut line). |
| `rag-service` (8084) | Keyword `QuestionParser`, `TemplatePhraser`, optional `ClaudePhraser` with digit check | No agent loop, no conversation memory, no tool calling. |

## Frontend
React 19 / Vite / TS. Dashboard, Accounts, Oracle, Consent use live services (or recorded answers in demo mode). Sign-in is a demo picker only. Oracle is single-turn.

## Tests
109 JUnit tests across modules (6 new contract tests). Evaluation: 1,380 dev + 705 held-out answerable questions, all exact; 0/150 consent leaks.

## Contracts drafted in this PR (no logic)
`AgentQueryPlan`, `ResultObject` (with `EmptyReason`), `Recommendation`, `Goal`, `InstrumentReference`, `EquityLot`, `FixedDeposit`, `ToolName`/`ToolDefinition`/`ToolResult`; five JSON tool schemas; TypeScript mirror in `frontend/src/types/oracle.ts`.

## Gaps and conflicts with the plan to decide
1. No FD or purchase-lot data exists; rules on maturities and long-term tax timing need generator and normaliser work first.
2. The plan's pgvector step conflicts with the repo's "no embeddings" claim; it stays the first thing cut.
3. Agent loop and conversation memory need an LLM; Claude via Satvik's own key, kept in env vars.
4. Account Aggregator data has no prices or sectors; instrument reference must be a separate synthetic/real file.
5. Services still trust any customer id.
