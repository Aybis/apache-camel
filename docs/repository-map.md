# Repository map

What every folder in this repository is for, in one place. Each folder listed here has its own
`README.md` with the details: what is inside, how it relates to the rest, and how to build, run or test it.

```
apache-camel/
├── pom.xml                              root Maven build: pins Java 25, Camel 4.22.1, Spring Boot 4.1.1
├── CLAUDE.md                            repository rules (read before adding or migrating a service)
├── config/                              configuration shared by the whole platform
│   ├── global/monitoring.yml            global logging, metrics, health and error-handling defaults
│   └── services.yml                     service registry: name, domain, port (maintained by the script)
├── platform/                            shared code every service depends on
│   └── camel-platform-starter/          the "global functions": logs, correlation ID, errors, metrics, console client
├── services/                            one folder per integration service (one deployable each)
│   ├── sample-service/                  reference service showing the conventions; simulates traffic
│   ├── payment-gateway/                 bank-neutral payment API with one adapter per bank (BNI first)
│   └── bank-simulator/                  local SNAP BI bank for development and tests; never deployed
├── console/                             management console (port 8090): settings, audit, status, logs, dashboard
├── deploy/                              local runtime stack: Docker Compose, Loki, Alloy, Prometheus, Grafana
├── templates/                           source files copied by scripts/new-service.sh
│   └── service/                         the skeleton of a new service (placeholders, not a real module)
├── scripts/                             new-service.sh, gen-compose.sh, stack.sh
└── docs/                                this map, the ACE mapping below, console and Grafana screenshots
```

## How the folders fit together

```
config/global/monitoring.yml ──packaged into──► platform/camel-platform-starter ──dependency of──► services/<name>
                                                                                                     │
templates/service ──copied by── scripts/new-service.sh ──registers in──► config/services.yml         │ JSON logs on stdout
                                                                │                                    │ /actuator/prometheus
                                                                ▼                                    ▼
                                         deploy/docker-compose.services.yml        deploy/ (Alloy ──► Loki, Prometheus ──► Grafana)
                                                                                                     ▲
console/ ◄── heartbeats, settings pulls ── services/<name>            console/ ── log search ────────┘
```

- A service never configures logging, metrics or error handling itself; it inherits them from the starter,
  which carries `config/global/monitoring.yml` as its lowest-precedence defaults.
- Configuration precedence, highest first: environment variables / `-D` → console store → the service's
  `application.yml` → `config/global/monitoring.yml`.
- Services write logs to stdout only. Grafana Alloy collects them; a Loki outage cannot block a service.

## Coming from IBM App Connect Enterprise

The mapping below is an orientation aid, not an equivalence. Several ACE behaviours (notably implicit MQ
transactions and backout) do not exist by default in Camel and must be configured on purpose; see
`CLAUDE.md` rule 3 and the standards document.

| ACE concept | Here | Note |
|---|---|---|
| Application / integration project | `services/<name>/` | One folder, one Maven module, one container image |
| Message flow | A `RouteBuilder` class in the service | Route ids follow `<service>-<purpose>` |
| Compute node (ESQL) | Java processors, beans and Camel EIPs | No reliable automatic ESQL converter was found; plan a manual rewrite |
| Shared library | `platform/camel-platform-starter` | Only cross-cutting concerns belong there, not business logic |
| Policy project / configurable service | `config/global/monitoring.yml`, the service `application.yml`, the console store | Connection settings live in Git; secrets come from the environment or a vault |
| BAR file | The service jar plus its container image (`deploy/Dockerfile.service`) | Built with `mvn package`; no deploy-time overrides inside the artifact |
| Integration server | One JVM / one container per service | Twenty services means twenty JVMs; watch memory |
| Integration node | The container platform (Docker Compose locally, Kubernetes later) | Kubernetes manifests are not in the repository yet |
| ACE Toolkit / Web UI | `console/` for settings and status, Grafana for dashboards and logs | |
| Activity log / user trace | ECS JSON logs in Loki, searchable by correlation ID | Business events via `PlatformLog.event` |
| MQ backout queue (BOTHRESH/BOQNAME) | The same MQ settings, with the transacted route configuration | Never combine a dead letter channel with a transacted route |

## Keeping this current

Every new folder ships with a `README.md` (rule in `CLAUDE.md`). When a folder is added, renamed or removed,
update this map in the same pull request.
