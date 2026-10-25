# Progress

**As of 25 October 2026.** Measured against the five architecture components and four technical
challenges in [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md). How to run everything is in the
[README](README.md).

## Architecture components

| # | Component | Status |
| --- | --- | --- |
| 1 | Mock data generator | Done. `data-generator` writes seeded, ReBIT-shaped FI fetch responses for deposits, mutual funds, insurance, EPF and equities |
| 2 | Aggregation / normalization | Done. Per-type normalisers in `fi-core`, served by `normalization`; simulated consent lifecycle in `ingestion` |
| 3 | Retrieval layer | Done, as structured filtering. Embeddings were dropped under the cut line because every supported question maps to a filter |
| 4 | RAG query service | Done. `rag-service` parses, retrieves, computes in Java and phrases with templates, or with Claude when a key is set |
| 5 | Query interface | Accounts, Oracle and Consent screens use the live services. Parts of the Dashboard are still mock |

## Technical challenges

| # | Challenge | How it is handled |
| --- | --- | --- |
| 1 | Numerical grounding | Retrieve-then-compute: `QueryEngine` computes every figure in `BigDecimal`; the model only sees placeholders. 0 of 1,725 evaluated answers contained a number not among the computed figures |
| 2 | Schema diversity | One `FinancialRecord` model with record kinds and a shared category vocabulary; one normaliser per FI type behind `FiNormalizer` |
| 3 | Consent simulation | Consents are checked before any record is fetched and limit FI types, providers and data range. Revoking deletes fetched data |
| 4 | Source traceability | Every figure lists its record ids, and every answer lists its citations and the consents used |

## Evaluation

See [evaluation/results/REPORT.md](evaluation/results/REPORT.md) and the summary in the README.

## Not done

Live AA integration, persistence, authentication, CI, and embedding-based retrieval.
