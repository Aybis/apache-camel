# camel-platform-starter

A Spring Boot starter that gives every service the platform's common behaviour simply by being on the
classpath. Services must not re-implement anything listed below.

**ACE equivalent:** a shared library plus the monitoring and error-handling behaviour an integration
server would otherwise provide.

## What it provides

| Class | Responsibility |
|---|---|
| `PlatformAutoConfiguration` | Registers the shared beans in every service |
| `PlatformEnvironmentPostProcessor` | Adds `config/global/monitoring.yml` as the lowest-precedence configuration and places console-store overrides above the service's own `application.yml` |
| `PlatformProperties` | The `platform.*` settings (environment, domain, console, correlation, error handling) |
| `PlatformRouteConfiguration` | Route policies applied to every route: correlation ID and a dead letter channel with exponential back-off; a `TRANSACTED` variant for IBM MQ consumers |
| `CorrelationIdProcessor` | Takes the correlation ID from `X-Correlation-Id`, else the JMS correlation ID, else generates one; puts it on the header, the exchange and the MDC |
| `PlatformLog` | Business events in ECS form: `PlatformLog.event(log, exchange, "order.received", "orderId", id)` |
| `ConsoleAgent`, `ConsoleHttp`, `ConsoleConfig`, `Heartbeat` | Pull settings from the console, apply log levels live, and send heartbeats with route states. Fail-open: an unreachable console never stops a service |

Together with `config/global/monitoring.yml` (packaged into this jar) it also sets ECS JSON logging on
stdout, Prometheus metrics tagged `service`, `domain` and `environment`, and Kubernetes liveness and
readiness probes.

## Using it

Services created with `scripts/new-service.sh` already depend on it. Rules for service code are in the
repository's `CLAUDE.md` (no own logging configuration, error handlers or metrics registries; pass the
class logger to the Log EIP; never add an MDC or ECS key as a key/value).

## Build and test

```bash
mvn -B -pl platform/camel-platform-starter -am verify
```

A change here reaches every service. Run the full `mvn -B verify` from the repository root before
opening a pull request, and say in the pull request which services' behaviour changes.
