#!/usr/bin/env bash
# Create a new service folder from templates/service and register it everywhere it must appear.
#
#   scripts/new-service.sh <service-name> [--domain <domain>] [--description "<text>"] [--local-only] [--database]
#
# --local-only marks a development/test helper (e.g. a partner simulator). It runs in the local
# stack but scripts/deployable-services.sh, and therefore any release or production manifest, skips it.
#
# --database gives the service its own PostgreSQL database (platform convention in CLAUDE.md): JDBC, Flyway
# and a first migration in src/main/resources/db/migration, a Testcontainers PostgreSQL test, and
# `database: true` in the registry, so the local stack creates the database and passes SPRING_DATASOURCE_*.
#
# Example:
#   scripts/new-service.sh order-sync --domain orders --description "Syncs orders from SAP to WMS"
#
# What it does:
#   1. copies templates/service to services/<service-name>, filling in names and packages
#   2. adds the module to services/pom.xml
#   3. registers the service (name, domain, port) in config/services.yml
#   4. regenerates deploy/docker-compose.services.yml
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME="${1:-}"
shift || true
DOMAIN="unassigned"
DESCRIPTION=""
LOCAL_ONLY="false"
DATABASE="false"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain) DOMAIN="$2"; shift 2 ;;
    --description) DESCRIPTION="$2"; shift 2 ;;
    --local-only) LOCAL_ONLY="true"; shift ;;
    --database) DATABASE="true"; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ ! "$NAME" =~ ^[a-z][a-z0-9-]{1,48}[a-z0-9]$ ]]; then
  echo "Service name must be kebab-case (a-z, 0-9, -), 3-50 chars, e.g. order-sync" >&2
  exit 2
fi
# Names that would collide with PostgreSQL's own roles/databases or the platform's own components.
case "$NAME" in
  postgres|console|db-provision|template0|template1|pg-*)
    echo "Service name '$NAME' is reserved (PostgreSQL or platform component)" >&2
    exit 2 ;;
esac
if [[ ! "$DOMAIN" =~ ^[a-z][a-z0-9-]*$ ]]; then
  echo "Domain must be lower-case kebab-case, e.g. orders" >&2
  exit 2
fi
TARGET="$ROOT/services/$NAME"
if [[ -e "$TARGET" ]]; then
  echo "services/$NAME already exists" >&2
  exit 1
fi
DESCRIPTION="${DESCRIPTION:-Integration service $NAME}"
DESCRIPTION="${DESCRIPTION//\"/\'}"

# order-sync -> package com.integration.camel.ordersync, class OrderSync
PACKAGE="com.integration.camel.$(echo "$NAME" | tr -d '-')"
PACKAGE_PATH="$(echo "$PACKAGE" | tr '.' '/')"
CLASS="$(echo "$NAME" | awk -F- '{for(i=1;i<=NF;i++) printf "%s%s", toupper(substr($i,1,1)), substr($i,2)}')"

# Next free port: highest registered port + 1, starting at 8101.
LAST_PORT="$(grep -E '^\s+port:' "$ROOT/config/services.yml" | awk '{print $2}' | sort -n | tail -1 || true)"
PORT=$(( ${LAST_PORT:-8100} + 1 ))

echo "Creating services/$NAME (package $PACKAGE, port $PORT, domain $DOMAIN)"

# 1. Copy the template (plus the database overlay with --database), renaming paths and replacing placeholders.
copy_template() {
  local src="$1"
  (cd "$src" && find . -type f ! -name README.md ! -name pom-dependencies.xml) | while read -r rel; do
    dest="$TARGET/$(echo "$rel" | sed -e "s#__PACKAGE_PATH__#$PACKAGE_PATH#" -e "s#__CLASS__#$CLASS#")"
    mkdir -p "$(dirname "$dest")"
    sed -e "s#__SERVICE__#$NAME#g" \
        -e "s#__PACKAGE__#$PACKAGE#g" \
        -e "s#__CLASS__#$CLASS#g" \
        -e "s#__DOMAIN__#$DOMAIN#g" \
        -e "s#__PORT__#$PORT#g" \
        -e "s#__DESCRIPTION__#$DESCRIPTION#g" \
        "$src/$rel" > "$dest"
  done
}
copy_template "$ROOT/templates/service"
if [[ "$DATABASE" == "true" ]]; then
  copy_template "$ROOT/templates/service-database"
  # Insert the database dependencies after the "Add the Camel components" line of the service pom.
  awk -v deps="$ROOT/templates/service-database/pom-dependencies.xml" '
    { print }
    /Add the Camel components this integration needs/ { print ""; while ((getline line < deps) > 0) print line }
  ' "$TARGET/pom.xml" > "$TARGET/pom.xml.tmp" && mv "$TARGET/pom.xml.tmp" "$TARGET/pom.xml"
  # Local default URL (database name = service name with - as _); the password comes only from the environment.
  DB_NAME="$(echo "$NAME" | tr '-' '_')"
  awk -v db="$DB_NAME" '
    { print }
    /^    name: / && !done { print "  datasource:"; print "    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/" db "}"; print "    username: ${SPRING_DATASOURCE_USERNAME:" db "}"; print "    hikari:"; print "      schema: " db; print "  flyway:"; print "    default-schema: " db; done=1 }
  ' "$TARGET/src/main/resources/application.yml" > "$TARGET/application.yml.tmp" \
    && mv "$TARGET/application.yml.tmp" "$TARGET/src/main/resources/application.yml"
fi

# 2. Add the module to services/pom.xml, keeping the list sorted (portable: no gawk needed).
POM="$ROOT/services/pom.xml"
MODULES="$( { sed -n 's#.*<module>\(.*\)</module>.*#\1#p' "$POM"; echo "$NAME"; } | sort -u)"
awk -v mods="$MODULES" '
  /<!-- services:begin -->/ { print; n=split(mods, m, "\n"); for (i=1;i<=n;i++) if (m[i]!="") printf "        <module>%s</module>\n", m[i]; skip=1; next }
  /<!-- services:end -->/ { skip=0 }
  !skip { print }
' "$POM" > "$POM.tmp" && mv "$POM.tmp" "$POM"

# 3. Register the service.
cat >> "$ROOT/config/services.yml" <<YAML
  - name: $NAME
    domain: $DOMAIN
    port: $PORT
    description: "$DESCRIPTION"
YAML
if [[ "$LOCAL_ONLY" == "true" ]]; then
  echo "    local-only: true" >> "$ROOT/config/services.yml"
fi
if [[ "$DATABASE" == "true" ]]; then
  echo "    database: true" >> "$ROOT/config/services.yml"
fi

# 4. Regenerate the compose file for services.
bash "$ROOT/scripts/gen-compose.sh"

echo "Done. Next: implement routes in services/$NAME/src/main/java/$PACKAGE_PATH/${CLASS}Routes.java"
