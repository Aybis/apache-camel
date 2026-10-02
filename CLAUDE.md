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
- Every top-level folder, Maven module and service ships with a `README.md` (subfolders are described in
  their parent's README): what it is for,
  what is inside, how it relates to the rest, how to build, run or test it, and the ACE equivalent where one
  exists. Adding, renaming or removing such a folder updates `docs/repository-map.md` in the same pull request.

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

## Rules for payments (services/payment-gateway)
Details and the API are in `services/payment-gateway/README.md`.
1. One bank-neutral API (`/api/payments/v1`). Bank-specific code lives only in `adapter/<bank>/`; the `api`,
   `spi`, `core` and `snap` packages never mention a bank.
2. Add a bank only with `bash services/payment-gateway/new-bank.sh <code> --bi-code <NNN> --name "..."`.
   SNAP BI banks extend `SnapBankAdapter` and override only what differs (`customize`, `paths`, response
   mapping); proprietary APIs implement `BankAdapter` (add `--proprietary`).
3. Never resend a money-moving request. Payment routes use the `payment-api` route configuration (no
   redelivery). A timeout, connection reset or 5xx is `UNKNOWN`, resolved only by status inquiry.
4. `clientReferenceId` is the idempotency key; a transfer is stored before it is sent.
5. Bank calls that can arrive twice (VA payment notifications) are applied once, keyed by the bank's payment id.
6. Credentials and private keys come only from the environment; dev keys come from
   `services/bank-simulator/dev/dev-keys.sh` and are git-ignored. bank-simulator is local/test only.
7. Wrap nested `ProducerTemplate` calls made inside a processor with `MdcScope.preserving(...)`, or the
   correlation ID disappears from the logs after the call.
8. Every payment behaviour change gets a case in `PaymentGatewayFlowTest`, which runs against the simulator.


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
