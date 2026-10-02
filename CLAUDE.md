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

## Known decisions (do not undo without reason)
- Camel 4.22.1 LTS + Spring Boot 4.1.1 + Java 21 (pinned in the root pom only).
- `camel.main.use-mdc-logging` is used although Camel marks it deprecated: camel-mdc (4.22.1) omits route and
  correlation ID on the error handler's "Failed delivery" lines. Re-test on each Camel upgrade.
- Services never push to Loki; Alloy collects stdout. A Loki outage cannot block message processing.
- The console agent starts on `ApplicationReadyEvent`, not as a `SmartLifecycle` bean: a lifecycle bean that
  depends on `CamelContext` makes Spring start Camel before routes are collected (0 routes started).
- Console is fail-open: unreachable console = service starts on local config.

## Build and verify
- `mvn -B verify` (all modules, tests). `bash scripts/stack.sh up` runs the local stack.
