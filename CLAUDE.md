# Repository conventions for Claude sessions

This monorepo holds the Apache Camel services that replace IBM App Connect Enterprise (ACE)
message flows. Read this before adding or migrating a service.

## Layout
- `config/global/monitoring.yml` — THE global monitoring/logging/error-handling config. Lowest
  precedence; packaged into the starter. Change shared behaviour here, not in services.
- `platform/camel-platform-starter` — shared "global functions" every service gets by depending on it:
  JSON (ECS) logs to stdout, correlation ID, default dead letter channel, metrics with
  `service/domain/environment` tags, health probes, console client (pulls settings, heartbeats).
- `services/<name>/` — one folder per service, created ONLY with `scripts/new-service.sh`.
- `console/` — management console (settings store + audit, status, log search over Loki, dashboard).
- `deploy/` — local stack (Loki, Prometheus, Alloy, Grafana, console, services). Dashboards are generated
  by `deploy/grafana/build_dashboards.py`; never hand-edit the JSON.
- `config/services.yml` — service registry; maintained by the script.

## Rules when writing a service
1. Create it: `bash scripts/new-service.sh <kebab-name> --domain <domain> --description "..."`.
2. Do NOT add logging config, logback.xml, error handlers, Micrometer registries or Loki appenders to a
   service. The starter provides them. A service's `application.yml` holds only what is specific to it.
3. Routes get the platform route configuration automatically (correlation ID + dead letter channel with
   exponential back-off). For a transactional IBM MQ consumer use
   `.routeConfigurationId(PlatformRouteConfiguration.TRANSACTED)` plus `.transacted()`, so failures roll back
   to the queue and MQ backout (BOTHRESH/BOQNAME) handles poison messages. Never put a DLC on a transacted route.
4. Business events: `PlatformLog.event(log, exchange, "order.received", "orderId", id)`. Fields land in
   `event.action` and `labels.*`. Never `addKeyValue` a key that also exists in MDC or ECS (e.g. `correlationId`,
   `service`): the JSON encoder drops the whole line.
5. Log EIP: pass the class logger, `.log(LoggingLevel.DEBUG, log, "...")`, otherwise the logger name is the
   route id and console log-level changes for the package do not apply.
6. Route ids: `<service>-<purpose>` (dashboards group by `routeId`).
7. Every service keeps a Camel test (`@CamelSpringBootTest`, `platform.console.enabled=false`).
8. Secrets never go in the console store or YAML; use env vars / Kubernetes Secrets / a vault. Connection
   settings (endpoints, hosts, queue managers, channels) live in Git; the console rejects such keys.
   Locally, a service's variables go in git-ignored `deploy/env/<service>.env` (or the service's own
   `dev/.keys/dev.env`); `scripts/gen-compose.sh` wires both in as optional `env_file`s.
9. Nested Camel calls: wrap a `ProducerTemplate` call made inside a processor in
   `com.integration.camel.platform.MdcScope.preserving(...)`. The nested exchange inherits the caller's
   correlation ID automatically; without `MdcScope` the caller's log lines after the call lose it.
10. Development/test helpers (simulators, mocks) are created with `new-service.sh ... --local-only`.
    Anything that releases or deploys takes its list from `scripts/deployable-services.sh`, never from `services/`.

11. Database (PostgreSQL 18; the image is pinned once, `postgres.image` in the root pom, for tests and the local stack):
    - Only when a service needs state: `new-service.sh ... --database`. It adds JDBC + Flyway, a first migration,
      a Testcontainers test and `database: true` in the registry. To add one later, copy what that flag
      generates (`templates/service-database/`) and set `database: true` in `config/services.yml`.
    - One database and one role per service, both named after the service with `-` as `_`
      (`order-sync` -> `order_sync`). A service never reads or writes another service's database; it calls its API.
    - Connection and credentials only from `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` (environment /
      Kubernetes Secret). Locally, the `db-provision` job creates the database and `gen-compose.sh` passes them.
    - Schema changes only as Flyway migrations in `src/main/resources/db/migration/V<n>__<what>.sql`, applied at
      start-up. Never edit a migration that has run anywhere. Breaking changes ship as expand, then contract.
    - Idempotency and money-safety rules are enforced with constraints (primary/unique keys), not only in code:
      insert the key first (`INSERT ... ON CONFLICT DO NOTHING`, act only if 1 row), act second. Never catch a
      unique violation to detect duplicates: it aborts the PostgreSQL transaction. Keep transactions short; never hold one open across a partner call.
    - Tables live in a schema named like the database (`order_sync`), owned by the service's role, never in
      `public`. The role's `search_path` is set to it in the database (provisioning); `new-service.sh --database`
      also sets `spring.flyway.default-schema` and the Hikari `schema` as a safeguard.
    - Per-role limits: `statement_timeout` 5s, `lock_timeout` 2s, `idle_in_transaction_session_timeout` 30s
      (set by provisioning, not server-wide). A slow migration sets its own `SET statement_timeout` first.
    - `JdbcClient` by default; JPA only when the model needs it.
    - Connection budget: pool defaults to max 10 / min idle 2 per instance (global config). Size
      `max_connections` for the sum of every instance's maximum (20 services x 2 replicas x 10 = 400) or put
      PgBouncer in front; raise a service's pool only with a load test that shows it waits for connections.
    - Tests run against real PostgreSQL via Testcontainers (`@ServiceConnection`), never H2.

## Rules for payments (services/payment-gateway)
Details and the API are in `services/payment-gateway/README.md`.
1. One bank-neutral API (`/api/payments/v1`). Bank-specific code lives only in `adapter/<bank>/`; the `api`,
   `spi`, `core` and `snap` packages never mention a bank.
2. Add a bank only with `bash services/payment-gateway/new-bank.sh <code> --bi-code <NNN> --name "..."`.
   SNAP BI banks extend `SnapBankAdapter` and override only what differs (`customize`, `paths`, response
   mapping); proprietary APIs implement `BankAdapter` (add `--proprietary`).
3. Never resend a money-moving request. Payment routes use the `payment-api` route configuration (no
   redelivery). A timeout, connection reset or 5xx is `UNKNOWN`, resolved only by status inquiry.
4. `clientReferenceId` is the idempotency key; a transfer is stored (committed in PostgreSQL) before it is sent.
   Money-safety rules are enforced by the database (keys, unique indexes, guarded UPDATEs, row locks); schema
   changes are new Flyway files in `src/main/resources/db/migration`, never edits to applied ones.
5. Bank calls that can arrive twice (VA payment notifications) are applied once, keyed by the bank's payment id.
6. Credentials and private keys come only from the environment; dev keys come from
   `services/bank-simulator/dev/dev-keys.sh` and are git-ignored. bank-simulator is local/test only.
7. Wrap nested `ProducerTemplate` calls made inside a processor with `MdcScope.preserving(...)` (the platform's
   `com.integration.camel.platform.MdcScope`; general rule 9), or the correlation ID disappears from the logs.
8. Every payment behaviour change gets a case in `PaymentGatewayFlowTest`, which runs against the simulator
   and a real PostgreSQL (Testcontainers; the build needs Docker).

## Known decisions (do not undo without reason)
- Camel 4.22.1 LTS + Spring Boot 4.1.1 + Java 25 LTS (pinned in the root pom only; images use
  eclipse-temurin:25-jre). Java 25 rules from the standards document (STD-JAVA-01..05):
  - Prove every third-party library on Java 25 before production (IBM MQ client support is not yet confirmed
    publicly; JDK 24+ has no Security Manager, so old libraries that install one fail).
  - Virtual threads (`camel.threads.virtual.enabled=true`) only per service, after a load test; keep explicit
    concurrency limits towards partners. Not enabled anywhere by default.
  - `-XX:+UseCompactObjectHeaders` per service via `JAVA_TOOL_OPTIONS`, after a soak test. Not on by default.
  - Never `--enable-preview` or incubator modules. Final features (records, pattern matching, scoped values) are fine.
  - JDK upgrades and Camel upgrades ship in separate releases.
- `camel.main.use-mdc-logging` is used although Camel marks it deprecated: camel-mdc (4.22.1) omits route and
  correlation ID on the error handler's "Failed delivery" lines. Re-test on each Camel upgrade.
- Services never push to Loki; Alloy collects stdout. A Loki outage cannot block message processing.
- The console agent starts on `ApplicationReadyEvent`, not as a `SmartLifecycle` bean: a lifecycle bean that
  depends on `CamelContext` makes Spring start Camel before routes are collected (0 routes started).
- Console is fail-open: unreachable console = service starts on local config.

## Build and verify
- `mvn -B verify` (all modules, tests). `bash scripts/stack.sh up` runs the local stack.
