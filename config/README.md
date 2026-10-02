# config

Configuration shared by the whole platform. Nothing here is specific to one service.

| File | Purpose | Who changes it |
|---|---|---|
| `global/monitoring.yml` | Global defaults for logging (ECS JSON), metrics, health probes, correlation ID, console polling and error handling (redeliveries, back-off, dead letter URI) | Platform owners, by pull request |
| `services.yml` | Service registry: name, business domain, host port and description of every service | `scripts/new-service.sh` only |

**ACE equivalent:** `global/monitoring.yml` plays the role of a shared policy project; `services.yml` is
roughly the list of applications deployed to an integration node.

## How it is used

- `global/monitoring.yml` is packaged into `platform/camel-platform-starter` at build time and loaded with
  the **lowest** precedence. A service's `application.yml`, the console store, environment variables and
  `-D` flags each override it. Change shared behaviour here, rebuild, and every service picks it up on its
  next release; do not copy these settings into services.
- `services.yml` feeds three consumers: the console seeds its service list from it,
  `scripts/gen-compose.sh` generates `deploy/docker-compose.services.yml` from it, and
  `scripts/new-service.sh` reads it to choose the next free port.

## Cautions

- Never put credentials, hosts, queue managers or other connection settings here. Secrets come from the
  environment or a vault; connection settings belong in each service's own configuration in Git.
- A change to `global/monitoring.yml` affects every service at once. Treat it as a platform release and
  review it accordingly.
- Do not hand-edit `services.yml` unless you also run `bash scripts/gen-compose.sh`, otherwise the
  local stack and the registry drift apart.
