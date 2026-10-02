# deploy

Everything needed to run the platform locally with Docker Compose: the services, the console and the
observability stack. It is a development and demonstration stack, not a production deployment.

**ACE equivalent:** the integration node and its runtime environment. A BAR deployment corresponds here to
building a service image from `Dockerfile.service`.

## Inside

| Path | Purpose |
|---|---|
| `docker-compose.yml` | Loki, Prometheus, Alloy, Grafana and the console, with their data volumes |
| `docker-compose.services.yml` | One entry per service. **Generated** by `scripts/gen-compose.sh` from `config/services.yml`; do not edit |
| `Dockerfile.service` | The single runtime image for every service (`eclipse-temurin:25-jre`, non-root user); built with `--build-arg SERVICE=<name>` |
| `Dockerfile.console` | Runtime image for the console |
| `alloy/config.alloy` | Grafana Alloy: discovers containers labelled `camel.platform.service`, ships their stdout to Loki and scrapes `/actuator/prometheus` |
| `loki/loki.yml` | Loki in single-process filesystem mode |
| `prometheus/prometheus.yml` | Prometheus only stores metrics; it scrapes nothing itself, Alloy remote-writes to it |
| `grafana/build_dashboards.py` | Generates the dashboards; edit this, then run `python3 deploy/grafana/build_dashboards.py` |
| `grafana/dashboards/` | **Generated** dashboard JSON (Camel platform overview, Camel service); do not edit by hand or save from the Grafana UI |
| `grafana/provisioning/` | Grafana provisioning of the Loki and Prometheus data sources and the dashboards folder |

## Run

```bash
bash scripts/stack.sh up      # builds jars and images, starts everything
bash scripts/stack.sh logs payment-gateway
bash scripts/stack.sh down    # stop, keep data
bash scripts/stack.sh reset   # stop and delete data volumes
```

Console http://localhost:8090, Grafana http://localhost:3000 (admin / admin), Prometheus http://localhost:9090.

## Cautions before using this beyond a laptop

- Grafana allows anonymous viewing and uses the default admin password.
- Loki runs in single-process filesystem mode; production needs object storage and a scalable mode.
- There are no Kubernetes manifests yet. On Kubernetes, Alloy should use `discovery.kubernetes` with pod
  labels instead of Docker labels, and `instance` should not be a Loki label.
- IBM MQ and a database are not part of this stack at the time of writing.
