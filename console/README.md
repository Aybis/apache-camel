# console

The management console: one web application (port 8090) to see every service, change its operational
settings, read its logs and open its dashboard.

**ACE equivalent:** the ACE Web UI and the configurable-service editing an administrator would do with
`mqsichangeproperties`, but limited to operational settings (see Cautions).

## What it offers

| Screen | Purpose |
|---|---|
| Services | Status from heartbeats (Up, Stale, Down, Never seen), instances, applied configuration version |
| Overview | Per instance: version, Camel version, applied configuration, every route's state |
| Configuration | Log levels (applied live) and properties (applied on next restart); each save is versioned with name and reason |
| Logs | Loki search by level, text and correlation ID, with a link to Grafana Explore |
| Dashboard | The Grafana service dashboard, embedded and filtered to the service |
| Audit trail | Every change: who, when, why |

The REST API behind it is under `/api` (`/api/services`, `/api/services/{name}/config`,
`/api/services/{name}/heartbeat`, `/api/services/{name}/logs`, `/api/audit`). Services use the config and
heartbeat endpoints through the starter's `ConsoleAgent`.

## Inside

| Path | Purpose |
|---|---|
| `src/main/java/.../ApiController.java` | REST API |
| `src/main/java/.../ConfigStore.java` | Settings store and append-only audit trail (`store.json`, `audit.jsonl` in `console.data-dir`) |
| `src/main/java/.../HeartbeatRegistry.java` | Latest heartbeat per instance, in memory |
| `src/main/java/.../LokiClient.java` | Log queries against Loki |
| `src/main/resources/static/` | The browser UI (plain HTML, CSS and JavaScript; no build step) |
| `src/main/resources/application.yml` | Data directory, registry path, Loki and Grafana URLs, write token |

## Run

```bash
mvn -B -pl console -am package
# The default registry path (../config/services.yml) is relative to the working directory, so set it
# explicitly when starting from the repository root; otherwise the service list starts empty.
CONSOLE_REGISTRY=config/services.yml java -jar console/target/console.jar     # http://localhost:8090
```

In the local stack it runs as the `console` container (`deploy/Dockerfile.console`).

## Cautions

- Security is minimal: only an optional shared write token (`CONSOLE_WRITE_TOKEN`). It needs SSO and
  role-based access before any shared environment.
- The store is a JSON file with a single writer, so only one console replica may run. A move to
  PostgreSQL is in progress.
- By design it refuses connection settings (URLs, hosts, ports, queue managers, channels) and credentials.
  Those belong in Git and in the environment or a vault.
- Services are fail-open: if the console is down they start on their local configuration, so the console
  is not on the message path.
