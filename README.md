# Etherm Finance

An Account Aggregator-style view of fragmented Indian financial data, with a question-answering
layer over the user's own records. Ask "How much did I spend on groceries last quarter?" and the
answer comes back with the figures, the records they were computed from, and the consents that
allowed reading them.

The people and accounts are synthetic, but fund NAVs and share prices are real public data (AMFI and
NSE), and spending proportions follow the MoSPI household survey. See
[reference data](data-generator/src/main/resources/reference/README.md). There is no live Account Aggregator integration: the ingestion service plays
the part of the FIPs and the consent manager. See [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) for the
original design intent.

## How a question is answered

The read path is **Allow → Find → Add → Phrase**:

1. **Allow.** The request names a customer or specific consent ids. Only `ACTIVE`, unexpired
   consents are used, and each one limits what can be read to its FI types, providers and data
   range. A revoked consent returns 403 and its fetched data is deleted.
2. **Find.** A deterministic parser turns the question into a plan: an intent (spend, income,
   premiums, net worth, holdings and so on), categories and a date range. Retrieval returns the
   normalised records that match the plan within the consents.
3. **Add.** Java computes every number in `BigDecimal`: totals, counts, breakdowns, top-N and
   comparisons with the previous period. Each figure carries the ids of the records it came from.
4. **Phrase.** A sentence is written around placeholders such as `{total}`, which are filled from
   the computed figures. By default a template writes it. Optionally Claude writes it, but it only
   sees placeholder names, and a draft containing any digit is rejected in favour of the template.

The language model never does arithmetic and never sees the amounts. This follows the
retrieve-then-compute approach motivated by numerical QA work such as FinQA and TAT-QA, on top of
retrieval-augmented generation (Lewis et al., 2020).

Retrieval is structured filtering over normalised records, not embedding search. Every question
this system supports maps to a filter, so embeddings were left out.

## Modules

| Module | Port | What it does |
| --- | --- | --- |
| `data-generator` | | Seeded synthetic FI fetch responses for five FI types (deposits, mutual funds, insurance, EPF, equities), shaped like ReBIT AA schemas |
| `fi-core` | | Shared model, per-type normalisers, consent scope checks, and the compute engine (`QueryEngine`) |
| `ingestion` | 8081 | Stores raw FI data; simulated consent manager (create, approve, refresh, revoke); provider discovery |
| `normalization` | 8082 | Normalises raw accounts into one record model, per consent |
| `retrieval` | 8083 | `POST /api/retrieve`: checks consents, then returns matching records |
| `rag-service` | 8084 | `POST /api/query`: parse, retrieve, compute, phrase |
| `frontend` | 5173 | React app: Dashboard, Accounts, AI Oracle and Consent screens |

## Run it

Requires Java 21, Maven 3.9+, Node 22, and Docker for the compose route.

```bash
# 1. Build and test the backend
mvn clean install

# 2. Generate the synthetic data (5 users, seed 42, 1 Oct 2025 to 30 Sep 2026)
mvn -pl data-generator exec:java -Dexec.args="--users 5 --seed 42 --out $PWD/data/synthetic"

# 3. Start the four services
docker compose up -d --build

# 4. Start the frontend
npm --prefix frontend install
npm --prefix frontend run dev
```

Open http://localhost:5173. The frontend's dev server proxies to the services on ports 8081 to
8084. When they are not running it falls back to the mock data in `frontend/src/data/mock.ts` and
says so.

Without Docker, start each jar in its own terminal instead of step 3:

```bash
ETHERM_SEED_DIR=$PWD/data/synthetic java -jar ingestion/target/ingestion-0.0.1-SNAPSHOT.jar
java -jar normalization/target/normalization-0.0.1-SNAPSHOT.jar
java -jar retrieval/target/retrieval-0.0.1-SNAPSHOT.jar
ETHERM_AS_OF=2026-10-25 java -jar rag-service/target/rag-service-0.0.1-SNAPSHOT.jar
```

### Settings

| Variable | Default | Effect |
| --- | --- | --- |
| `ETHERM_SEED_DIR` | `../data/synthetic` | Where ingestion loads generated data from at startup |
| `ETHERM_AS_OF` | today (compose pins `2026-10-25`) | The date "last month" and "last quarter" are resolved against. Pinning it keeps relative questions inside the synthetic data window |
| `ETHERM_PHRASING` | `template` | Set to `claude` to have Claude write the sentence |
| `ANTHROPIC_API_KEY` | | Required when `ETHERM_PHRASING=claude` |

### Try it

```bash
curl -s localhost:8084/api/query -H 'Content-Type: application/json' \
  -d '{"customerId":"user001@etherm-aa","question":"Did my insurance premiums increase last quarter compared to the one before?"}'
```

The response has `answer` (the sentence), `figures` (the authoritative numbers), `breakdown`,
`citations` (one per record used) and `grounding` (which consents were used and how many records
were visible, matched and cited).

Each synthetic user starts with one active consent per provider, for example `CN-user001-BANK` and
`CN-user001-INSURER`. Pass `consentIds` instead of `customerId` to answer under specific consents
only.

## Evaluation

`evaluation/evaluate.py` computes the expected answer to each question in Python, straight from
the raw FI JSON, without using any of the Java code. It then asks the running service and scores
the response. Run it with the services up:

```bash
python3 evaluation/evaluate.py
```

Latest results ([full report](evaluation/results/REPORT.md)):

| Metric | Dev set | Held-out set |
| --- | --- | --- |
| Answerable questions | 1,140 | 585 |
| Intent accuracy | 100% | 100% |
| Exact-match answers, to the paisa | 100% | 100% |
| Citation precision / recall | 1.000 / 1.000 | 1.000 / 1.000 |
| Sentences containing a number not among the computed figures | 0 | 0 |
| Out-of-scope questions declined | 40 of 40 | 25 of 30 |

Answers under a single provider's consent cited another provider 0 times in 150 questions, and a
revoked consent was refused both times it was tried.

Before the parser fixes the evaluation prompted, intent accuracy was 89.5% (dev) and 93.2%
(held-out), and 6 of 14 advice or out-of-scope questions were answered with figures instead of
being declined ([baseline](evaluation/results/BASELINE.md)). Once the intent and period were
parsed correctly, the computed answer was exact in 99.5% and 100% of cases. The one exception was a
parser bug that read "portfolio" as a mutual fund folio.

These numbers have limits. The questions come from 58 answerable phrasings, repeated across 8
periods and 5 users, so the wording is much less varied than the count suggests. The same people
wrote the parser and the questions. The remaining miss ("How do I file my income tax return?" is
answered as an income question) is left in to show what the keyword parser still gets wrong.

## Frontend

`frontend/` is a Vite, React and TypeScript app built on the Etherm Lumina design system in
[docs/design/DESIGN.md](docs/design/DESIGN.md).

| Route | Screen | Data |
| --- | --- | --- |
| `/accounts` | Linked Accounts | Live: accounts, balances and sync time come from the services. Linking a provider creates and approves a consent; removing one revokes it |
| `/oracle` | AI Oracle | Live: questions go to `POST /api/query`, and answers show figures, a breakdown and record citations |
| `/consent` | Consent & Security | Live consent ledger. The profile and active sessions are mock |
| `/` | Dashboard | Live: net worth, allocation, a 12-month history rebuilt from transactions at today's prices (market moves are not reflected), recent activity, and three insights answered by the same pipeline as the Oracle (`GET /api/customers/{id}/overview`) |

Sign-in is a demo picker for the five synthetic customers (`src/lib/session.ts`); no password is
checked and the services still accept any customer id, so this is not access control yet. Each
user's session reloads their own accounts, Dashboard and Oracle data.

Privacy Mode (on `/consent`) masks every monetary figure in the app through
`src/state/PrivacyContext.tsx`. New money values should render through `<Money />` to keep that
behaviour.

## Not built

- No live Account Aggregator, FIP or consent-manager integration; payloads are unencrypted.
- No embeddings or vector search (see above).
- No CI pipeline.
- No persistence: the services keep data in memory and reload the synthetic data on restart.
- No authentication: any caller can query any synthetic customer.
