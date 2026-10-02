#!/usr/bin/env bash
# Build and run the whole local stack.
#   scripts/stack.sh up      build jars + images, start everything
#   scripts/stack.sh down    stop (keeps data volumes)
#   scripts/stack.sh reset   stop and delete data volumes
#   scripts/stack.sh logs <service>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/deploy/docker-compose.yml" -f "$ROOT/deploy/docker-compose.services.yml")

case "${1:-up}" in
  up)
    # Local database passwords: generated once into git-ignored deploy/.env, which Compose reads.
    if [[ ! -f "$ROOT/deploy/.env" ]]; then
      ( # subshell: the restrictive umask must not apply to the jars built below
        umask 077
        echo "POSTGRES_PASSWORD=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" > "$ROOT/deploy/.env"
        echo "SERVICE_DB_PASSWORD=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')" >> "$ROOT/deploy/.env"
      )
      echo "Generated local database passwords in deploy/.env"
    fi
    (cd "$ROOT" && mvn -B -q package -DskipTests)
    "${COMPOSE[@]}" up -d --build
    echo
    echo "Console:    http://localhost:8090"
    echo "Grafana:    http://localhost:3000   (admin / admin, anonymous viewing on)"
    echo "Prometheus: http://localhost:9090"
    ;;
  down) "${COMPOSE[@]}" down ;;
  reset) "${COMPOSE[@]}" down -v ;;
  logs) "${COMPOSE[@]}" logs -f "${2:?service name}" ;;
  *) echo "usage: $0 up|down|reset|logs <service>" >&2; exit 2 ;;
esac
