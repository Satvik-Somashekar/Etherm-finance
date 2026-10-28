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
   savings rate, top merchants, monthly trend, premiums, net worth, holdings and so on), categories and a date range. Retrieval returns the
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
| `ETHERM_PHRASING` | `template` | Set to `claude` or `gemini` to have that model write the sentence |
| `ANTHROPIC_API_KEY` | | Required when `ETHERM_PHRASING=claude` |
| `GEMINI_API_KEY` | | Required when `ETHERM_PHRASING=gemini` |
| `ETHERM_GEMINI_MODEL` | `gemini-3.5-flash` | Gemini model used for phrasing |
| `ETHERM_TURN_KEY` | made at start-up | Base64 key (32 bytes or more) that signs answers, so earlier turns can be checked; set it to keep conversations valid across restarts |

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
| Answerable questions | 1,380 | 705 |
| Intent accuracy | 100% | 100% |
| Exact-match answers, to the paisa | 100% | 100% |
| Citation precision / recall | 1.000 / 1.000 | 1.000 / 1.000 |
| Sentences containing a number not among the computed figures | 0 | 0 |
| Out-of-scope questions declined | 40 of 40 | 30 of 30 |

Answers under a single provider's consent cited another provider 0 times in 150 questions, and a
revoked consent was refused both times it was tried.

Before the parser fixes the evaluation prompted, intent accuracy was 89.5% (dev) and 93.2%
(held-out), and 6 of 14 advice or out-of-scope questions were answered with figures instead of
being declined ([baseline](evaluation/results/BASELINE.md)). Once the intent and period were
parsed correctly, the computed answer was exact in 99.5% and 100% of cases. The one exception was a
parser bug that read "portfolio" as a mutual fund folio.

The savings rate, top merchants and monthly trend questions were added after the held-out set was
written, so their held-out phrasings were written alongside the code and are not a blind test.

These numbers have limits. The questions come from 67 answerable phrasings, repeated across 8
periods and 5 users, so the wording is much less varied than the count suggests. The same people
wrote the parser and the questions. The last held-out miss ("How do I file my income tax return?" was
answered as an income question) was fixed by a tax-planning rule added during the red-team work below, so the
held-out out-of-scope set is no longer a blind test for that wording.

## Security testing

The model never sees raw records, but it does see payee names in lookup results, and those come from
statements a stranger can influence: anyone who receives a UPI payment chooses the name that appears.
So the Oracle treats every payee name as untrusted, and checks what reaches the customer whatever the
model was told.

- **Payee names** are normalised (full-width letters folded, zero-width and direction-override
  characters removed), stripped of markup, shortened to 48 characters, and withheld as "a payee (name
  withheld)" when they read like an instruction, a role marker, a tool name, a link or markup
  (`UntrustedText`).
- **Replies** are refused when they contain a digit of the model's own, an amount in words ("twelve lakh"),
  a link, an image or HTML, or a placeholder that does not exist. A refused reply is sent back once, then the
  template wording is used.
- **Earlier turns** sent back by the browser only count as the assistant's words when they carry the
  signature the service gave that answer (HMAC-SHA256, bound to the customer; key in `ETHERM_TURN_KEY`, or
  made at start-up). Without it a client could make the model "repeat" a number nobody computed.
- **Tools** take no customer argument, run through the same consent and section checks as typed questions,
  and are only offered for the selected section.

Two red-team suites check this. `RedTeamTest` plants 45 payee names (37 attacks, 8 ordinary names) in a
customer's statement and runs the chat against a deliberately gullible model that obeys any unusual name it
sees. The name filter caught 30 of the 37 attacks and hid none of the ordinary names; the 7 it missed
(paraphrases, base64, Hindi, Cyrillic look-alikes, an amount in words) reached the model, and every one of
the 45 answers was still safe: no link or markup, no number Java did not compute, nothing cited outside the
section. `evaluation/redteam.py` has each customer ask 20 attack questions through both endpoints with a
forged assistant turn; 200 of 200 responses passed ([report](evaluation/results/REDTEAM.md)). Without a
model key those questions take the template path, so the live suite should be re-run with
`GEMINI_API_KEY` set.

These tests show the layers hold against the payloads written here, not against every attack: the name
filter is a keyword list, and the checks on replies are what the guarantee rests on.

### Supply chain and credentials

- `python3 scripts/secret-scan.py` checks every tracked file for API keys, tokens, private keys and
  passwords (`--history` checks every version ever committed; `--staged` is what the pre-commit hook runs).
  Enable the hook once per clone with `git config core.hooksPath .githooks`.
- Maven fails the build on an unpinned plugin, diverging dependency versions or a library with a known
  critical flaw (Log4Shell, Text4Shell, commons-collections gadgets). `mvn -Psbom package` writes a
  CycloneDX bill of materials; `npm --prefix frontend sbom --sbom-format cyclonedx` does the same for the
  frontend.
- Frontend versions are exact and `npm audit` reports no known vulnerabilities (Vite was raised from 8.2.2 to
  8.3.5 for six advisories on its dev server).
- `.github/workflows/ci.yml` runs the secret scan over the whole history, the backend build and tests, the
  accuracy evaluation and both red-team suites against running services, and the frontend lint, build and
  audit. Actions are pinned to commit SHAs and the workflow can only read the repository. Dependabot proposes
  updates weekly.
- The container image is pinned by digest and runs as a non-root user with a read-only filesystem and no
  capabilities; compose publishes ports on 127.0.0.1 only. The Docker changes were not built here (no Docker
  daemon in this environment).

See [SECURITY.md](SECURITY.md) for how to report a problem.

## Frontend

`frontend/` is a Vite, React and TypeScript app built on the Etherm Lumina design system in
[docs/design/DESIGN.md](docs/design/DESIGN.md).

| Route | Screen | Data |
| --- | --- | --- |
| `/accounts` | Linked Accounts | Live: accounts, balances and sync time come from the services. Linking a provider creates and approves a consent; removing one revokes it. The Export card downloads transactions and a monthly report as CSV |
| `/oracle` | AI Oracle | Live: questions go to `POST /api/query`, and answers show figures, a breakdown and record citations |
| `/consent` | Consent & Security | Live consent ledger for the signed-in customer |
| `/` | Dashboard | Live: net worth, allocation, a 12-month history rebuilt from transactions at today's prices (market moves are not reflected), recent activity, and three insights answered by the same pipeline as the Oracle (`GET /api/customers/{id}/overview`) |

Sign-in is a demo picker for the five synthetic customers (`src/lib/session.ts`); no password is
checked and the services still accept any customer id, so this is not access control yet. Each
user's session reloads their own accounts, Dashboard and Oracle data.

### CSV exports

`GET /api/customers/{id}/export/transactions.csv` (optional `from`, `to`, `category`, `fiType`) returns
every consented transaction, one row each, oldest first. `GET /api/customers/{id}/export/report.csv`
(optional `from`, `to`) returns one row per month with income, spending, saved, savings rate and
spending by category, plus a total row. Both read only what the customer's active consents allow, and the
report uses the same compute layer as the Oracle, so its figures match an Oracle answer for the same
period. Text taken from records is defused against spreadsheet formulas by a leading apostrophe.
Checked against `evaluation/evaluate.py`'s independent ground truth for all five customers and 12
months. The Oracle can also produce them: "Export my grocery transactions for last quarter" or "Generate a monthly report" returns an answer with the row count (computed in Java) and a Download button that fetches the same file. Exports are off in demo mode, which has no backend.

### Demo mode (no backend)

`npm --prefix frontend run build:demo` builds a static site that serves recorded responses instead of
calling the services, so it can be hosted anywhere (`frontend/vercel.json` has the Vercel settings).
The recordings in `frontend/public/demo/` were captured from the real services by
`npm --prefix frontend run record-demo` (services up, `ETHERM_AS_OF=2026-10-25`): accounts,
providers, the Dashboard overview, and answers to the 18 questions in `frontend/src/demo/questions.json`
for each of the five customers. Nothing is computed in the browser.

Limits: only those 18 questions are answered; other questions get a message saying so. Revoking a
consent works in the session (the account disappears and Oracle answers stop until it is linked
again) but the Dashboard history is not recomputed, and a reload resets everything.

Privacy Mode (on `/consent`) masks every monetary figure in the app through
`src/state/PrivacyContext.tsx`. New money values should render through `<Money />` to keep that
behaviour.

## Not built

- No live Account Aggregator, FIP or consent-manager integration; payloads are unencrypted.
- No embeddings or vector search (see above).
- CI is defined in `.github/workflows/ci.yml`; its first runs are on the draft PRs, so treat it as new.
- No persistence: the services keep data in memory and reload the synthetic data on restart.
- No authentication: any caller can query any synthetic customer.
