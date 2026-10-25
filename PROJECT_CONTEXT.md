# Open Finance Aggregator — Project Context

## One-line summary
A platform that aggregates fragmented Indian financial data (via India's Account Aggregator framework, mocked for now) and layers a RAG-based conversational interface on top so users can ask natural-language questions instead of reading dashboards.

## Problem
Financial data in India is fragmented across banks, insurers, pension providers, and investment platforms. Users juggle multiple apps to understand their overall financial position. India's Account Aggregator (AA) framework — RBI-regulated, consent-based data-sharing infrastructure — solves this at the plumbing level, but today it's used almost entirely for backend lending/credit-assessment use cases, not consumer-facing visibility tools.

## Solution
Two layers:
1. **Aggregation layer** — ingests financial data in AA-compliant schemas (deposits, mutual funds, insurance, EPF, equities) and normalizes it into one internal representation.
2. **Conversational query layer** — a RAG pipeline sitting on top of the aggregated data, answering direct natural-language questions ("How much did I spend on insurance premiums last quarter?") grounded in the user's actual retrieved records rather than the model's parametric knowledge — critical for avoiding hallucinated numbers in a financial context.

## Why mock data first
Live AA integration requires becoming/partnering with a licensed Financial Information User, managing real consent lifecycles, and handling real PII under RBI rules. None of that is needed to validate the core idea. So: build and validate against realistic **mock data** that mirrors AA's actual schema specs (per Sahamati's FI data specifications), and treat live AA integration (via a sandbox like Setu, Finvu, or Anumati) as a later-phase extension.

## Tech stack
- **Backend**: Spring Boot (Java) — REST APIs, service orchestration
- **Containerization**: Docker
- **CI/CD**: GitLab CI or Jenkins
- **RAG layer**: reusing/adapting an existing Financial RAG model (previously built for a client's financial-document validation MVP) — embeddings + retrieval over structured financial records, then LLM generation grounded in retrieved context
- **Data**: mock data generated to match AA FI (Financial Information) schema specs — deposits, mutual funds, insurance policies, EPF

## Architecture components to build
1. **Mock data generator** — produces synthetic but schema-accurate records for each FI type (deposits, mutual funds, insurance, EPF). Should mirror real AA JSON schemas so swapping in live data later is low-effort.
2. **Aggregation/normalization service** (Spring Boot) — ingests mock FI data across types, normalizes into a unified internal model, handles simulated consent artifacts (time-bound, purpose-bound access — even though mocked, model this properly).
3. **Retrieval layer** — embeds structured financial records, retrieves relevant subset (transactions/holdings/policies) based on a user query.
4. **RAG query service** — takes user's natural-language question, retrieves relevant records, constructs grounded prompt, calls LLM, returns answer with source traceability (which records were used).
5. **Query interface** — conversational front end (chat-style) where users ask questions and see grounded answers.

## Key technical challenges to solve in code
1. **Numerical grounding / aggregation** — many questions need sums, averages, comparisons across time periods, not just single-record lookup. This is retrieval + computation, not pure RAG lookup. Need a strategy (e.g., structured query generation, or retrieve-then-compute in code rather than trusting the LLM to do arithmetic).
2. **Schema diversity** — AA data spans multiple FI types with different schemas. Normalization layer needs to be flexible/extensible, not hardcoded per-type parsers.
3. **Consent simulation** — even with mock data, model consent scoping (time-bound, purpose-bound) so the architecture doesn't need a redesign when real AA integration happens later.
4. **Source traceability** — every RAG answer should be able to point back to which specific records it used.

## Later-phase (not in current scope)
Live AA integration via a sandbox (Setu / Finvu / Anumati), real consent artifact handling, real PII security requirements.

## My background (for calibrating suggestions)
- Comfortable with Java/Spring Boot, Docker, CI/CD (Jenkins, GitLab CI, ArgoCD) from a fintech internship (REST APIs, microservices)
- Already have a working Financial RAG model from prior client work — this project reuses/extends it rather than building RAG from scratch
- Less experience with functional programming (not relevant here, just context)
- Building this partly as a portfolio project aligned with fintech/AI engineering roles
