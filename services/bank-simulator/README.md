# bank-simulator

A local imitation of a bank that speaks Bank Indonesia's SNAP BI standard, with a BNI profile. It lets
`payment-gateway` be developed and tested without a bank sandbox. **It is for local and test use only and
must never be deployed to a shared or production environment.**

**ACE equivalent:** none directly; comparable to a stub or mock endpoint used during flow testing.

## What it does

- Serves the SNAP endpoints the gateway calls (B2B access token, intrabank and interbank transfer,
  transfer status inquiry, create virtual account)
  under the `/bni` prefix, verifying signatures with the gateway's public key.
- Sends virtual-account payment notifications back to the gateway (`/inbound/bni/...`), signed with its
  own key. `POST /sim/va-payments` triggers such a payment.
- Chooses an outcome from the cents of the amount, so tests can reach each path deterministically:
  `.13` insufficient funds, `.44` internal error (transfer not recorded), `.55` pending then success on a
  later status inquiry, `.77` processed but answered too late, to provoke a client timeout; anything else
  succeeds.

The exact SNAP contract BNI uses is assumed from the SNAP standard. The simulator therefore proves the
gateway against the standard, not against BNI; confirm against BNI's sandbox before go-live.

## Relationship to payment-gateway

`payment-gateway`'s tests compile this module's sources into their test context (see the
`build-helper-maven-plugin` section of `payment-gateway/pom.xml`), so a change here can break the gateway's
build. Run the gateway's tests after changing the simulator.

## Run locally

```bash
bash services/bank-simulator/dev/dev-keys.sh              # throwaway keys in dev/.keys/ (git-ignored)
set -a; source services/bank-simulator/dev/.keys/dev.env; set +a
mvn -B -q -pl services/bank-simulator,services/payment-gateway -am package -DskipTests
java -jar services/bank-simulator/target/bank-simulator.jar &   # port 8103
java -jar services/payment-gateway/target/payment-gateway.jar & # port 8102
```

| Path | Purpose |
|---|---|
| `dev/dev-keys.sh` | Generates throwaway RSA keys and secrets for both sides; never use them outside your machine |
| `src/main/resources/application.yml` | Prefix, slow-response delay, partner keys and callback target, all from environment variables |
