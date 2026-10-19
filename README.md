# Open Finance Aggregator

Backend for the Open Finance Aggregator. The Maven reactor contains:

- `data-generator` — seeded synthetic data for five FI types (deposit, mutual funds, insurance, EPF, equities), shaped like ReBIT Account Aggregator FI fetch responses. Payloads are unencrypted and every person, account and policy is invented.
- `ingestion` (port 8081) — validates FI fetch responses and stores the raw accounts in memory. Loads generated data at startup.
- `normalization` (port 8082) — scaffold only
- `retrieval` (port 8083) — scaffold only
- `rag-service` (port 8084) — scaffold only

There is no live AA integration; all data is synthetic.

An API gateway is intentionally deferred until the services expose public APIs that need centralized routing.

## Build

Requires Java 21 and Maven 3.9+.

```bash
mvn clean install
```

## Generate synthetic data

```bash
mvn -pl data-generator exec:java -Dexec.args="--users 5 --seed 42 --out $PWD/data/synthetic"
```

The same seed always produces the same data. The default window is 1 Oct 2025 to 30 Sep 2026; change it with `--from` and `--to`.

## Run the ingestion service

```bash
ETHERM_SEED_DIR=$PWD/data/synthetic java -jar ingestion/target/ingestion-0.0.1-SNAPSHOT.jar
curl http://localhost:8081/api/customers
curl "http://localhost:8081/api/customers/user001@etherm-aa/accounts?fiType=DEPOSIT"
```

`POST /api/fi/fetch-responses` accepts one fetch response and returns 422 with a list of problems if it is malformed.

Verify it is running:

```bash
curl http://localhost:8081/actuator/health
```

Expected response:

```json
{"status":"UP"}
```
