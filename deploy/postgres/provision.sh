#!/bin/sh
# Creates one database and one role per service that has `database: true` in config/services.yml,
# plus the console's. Idempotent: runs on every `docker compose up` (service db-provision) and only
# creates what is missing (and resets role passwords to SERVICE_DB_PASSWORD), so a service added later gets its database on the next start.
# Local stack only. Shared environments provision databases through the DBA/infrastructure process.
set -eu
REGISTRY=/config/services.yml
PASSWORD="${SERVICE_DB_PASSWORD:?}"

dbs=$(awk '
  /^  - name:/ { if (db == "true") print name; name=$3; db="false" }
  /^    database:/ { db=$2 }
  END { if (db == "true") print name }
' "$REGISTRY")

for svc in console $dbs; do
  db=$(echo "$svc" | tr '-' '_')
  echo "Ensuring database and role $db"
  psql -v ON_ERROR_STOP=1 -v db="$db" -v pw="$PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'db')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'db') \gexec
-- Every run, so a changed SERVICE_DB_PASSWORD reaches existing roles.
SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'db', :'pw') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'db')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db') \gexec
SQL
done
