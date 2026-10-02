# payment-gateway

One bank-neutral payment API in front of any number of banks. BNI is the first bank. Callers never see
bank-specific formats, signatures or response codes; each bank sits behind one adapter.

```
 caller ──► /api/payments/v1 ──► PaymentService (validation, idempotency, store, events)
                                        │
                                        ▼  BankAdapter (spi)
                         ┌──────────────┴──────────────┐
                    SnapBankAdapter (SNAP BI)     your proprietary adapter
                         │
                      BniAdapter ──► BNI SNAP API (token, signatures, timeouts)
 BNI ──► /inbound/bni/... (access token, VA payment notification) ──► adapter verifies ──► core decides
```

## API

| Method and path | Purpose |
|---|---|
| `POST /api/payments/v1/transfers` | Send a transfer. Same-bank code = intrabank (SNAP 17), otherwise interbank (SNAP 18). |
| `GET /api/payments/v1/transfers/{id}?refresh=true` | Read it; `refresh` asks the bank when not final. |
| `POST /api/payments/v1/virtual-accounts` | Open a virtual account (closed amount, or open if `amount` is omitted). |
| `GET /api/payments/v1/virtual-accounts/{id}` | Read it; `status` becomes `SUCCESS` when paid. |
| `GET /api/payments/v1/banks` | Enabled banks and their capabilities. |
| `POST /inbound/{bank}/...` | Called by banks only (SNAP access token and VA payment notification). |

```json
POST /api/payments/v1/transfers            (header X-Api-Key when payment.api-key is set)
{"bank":"bni","clientReferenceId":"PO-2026-0001","sourceAccount":"1234567890",
 "beneficiaryBankCode":"014","beneficiaryAccount":"5550001111","beneficiaryName":"Andi",
 "amount":250000,"remark":"PO-2026-0001"}
```

`status` is one of `SUCCESS`, `FAILED`, `PENDING` (bank accepted, still processing) or `UNKNOWN`
(the request may have reached the bank but no definite answer came back). `PENDING` and `UNKNOWN` are
resolved by the reconciliation route, which asks the bank for the status; the gateway never re-sends a
transfer. Callers must treat `UNKNOWN` as "do not retry with a new reference".

`clientReferenceId` is the idempotency key: the same reference with the same content returns the stored
result (HTTP 200) without contacting the bank; the same reference with different content is refused (409).

## Adding a bank

```bash
bash services/payment-gateway/new-bank.sh mandiri --bi-code 008 --name "Bank Mandiri"
bash services/payment-gateway/new-bank.sh legacybank --bi-code 999 --name "Legacy Bank" --proprietary
```

The script writes `adapter/<code>/<Code>Adapter.java` and a `payment.banks.<code>` block in
`application.yml`. A SNAP BI bank then works with configuration only (verified with a generated adapter
against the simulator); override `customize(...)`, `paths` or the response mapping only where the bank
deviates from SNAP. A proprietary bank implements `BankAdapter` directly and declares what it supports.
The core, the API and the routes do not change.

## Configuration and secrets

Connection settings (`base-url`, paths, timeouts) are in `application.yml`, in Git. Credentials come only
from the environment: `BNI_CLIENT_KEY`, `BNI_CLIENT_SECRET`, `BNI_PRIVATE_KEY` (PKCS#8 PEM),
`BNI_PARTNER_ID`, `BNI_CHANNEL_ID`, `BNI_VA_PARTNER_SERVICE_ID`, and for calls from BNI
`BNI_INBOUND_CLIENT_KEY`, `BNI_INBOUND_CLIENT_SECRET`, `BNI_INBOUND_PUBLIC_KEY`. Set `BNI_ENABLED=true`.

## Database

PostgreSQL, schema `payment_gateway`, created and migrated by Flyway at start-up from
`src/main/resources/db/migration`. Connection from `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`. The database enforces the money-safety rules itself, so they hold across replicas:

| Rule | How |
|---|---|
| A client reference is sent at most once | `transfer.id` primary key; `INSERT ... ON CONFLICT DO NOTHING` decides which request sends |
| Saved before sending | the insert is its own committed statement, before the bank is called |
| A final status is never overwritten | every UPDATE is guarded by `status IN ('PENDING','UNKNOWN')` (or `<> 'SUCCESS'` for accounts) |
| A payment notification is credited once | row lock (`SELECT ... FOR UPDATE`) on the account while deciding; unique index on (bank, payment id) |
| Money is exact | `NUMERIC(19,2)`, status values constrained by CHECK |

Schema changes are new migration files (`V2__...sql`); never edit an applied one.

## Running locally against the simulator

```bash
docker run -d --name payments-db -p 5432:5432 -e POSTGRES_DB=camel -e POSTGRES_USER=camel \
  -e POSTGRES_PASSWORD=camel postgres:17-alpine
export SPRING_DATASOURCE_USERNAME=camel SPRING_DATASOURCE_PASSWORD=camel
mvn -B -pl services/bank-simulator,services/payment-gateway -am package
bash services/bank-simulator/dev/dev-keys.sh                 # throwaway keys, git-ignored
set -a; source services/bank-simulator/dev/.keys/dev.env; set +a
java -jar services/bank-simulator/target/bank-simulator.jar &     # :8103
java -jar services/payment-gateway/target/payment-gateway.jar &   # :8102
curl -X POST localhost:8103/sim/va-payments -d '{"virtualAccountNo":"988297001"}'   # simulate a payment
```

The simulator chooses its behaviour from the amount's cents: `.13` insufficient funds, `.44` bank error
with nothing recorded, `.55` accepted then pending, `.77` processed but answered too late (timeout), any
other value succeeds. `PaymentGatewayFlowTest` runs all of these against the simulator and a real PostgreSQL (Testcontainers, so
the build needs Docker), including concurrent duplicate requests and duplicate notifications.

## What this version deliberately leaves out

| Gap | Risk | What production needs |
|---|---|---|
| Events sent synchronously | If the events endpoint is down, the event goes to the dead letter log only. | Transactional outbox. |
| BNI contract from the SNAP standard | BNI may differ in paths, mandatory `additionalInfo` fields, or run virtual accounts on BNI eCollection instead of SNAP. | Check against BNI's portal and signature test vectors at onboarding. |
| Interbank without prior account inquiry | A wrong beneficiary name is only caught by the bank. | SNAP account inquiry (service 16) before interbank transfers. |
| "Not found" becomes FAILED after 10 minutes | If BNI's status API lags longer, a processed transfer could be reported failed. | Confirm BNI's guidance; tune `not-found-grace`. |
| Single shared API key | No per-client identity or limits. | OAuth2 or mTLS at the API gateway, per-client limits, amount limits. |
| No end-of-day reconciliation | Status inquiry covers single transfers only. | Daily matching against BNI's statement (SNAP bank statement API). |
