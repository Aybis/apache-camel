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
-- Schema per service (CLAUDE.md rule 11) as the role's default search_path, set server-side so it also
-- holds behind PgBouncer in transaction mode (Hikari's `schema` is only a second safeguard).
SELECT format('ALTER ROLE %I IN DATABASE %I SET search_path = %I', :'db', :'db', :'db') \gexec
-- Starting limits per role, not server-wide (standards STD-DB-08). Flyway runs as the same role locally:
-- a migration that needs longer (e.g. an index build) starts with its own SET statement_timeout.
SELECT format('ALTER ROLE %I SET statement_timeout = %L', :'db', '5s') \gexec
SELECT format('ALTER ROLE %I SET lock_timeout = %L', :'db', '2s') \gexec
SELECT format('ALTER ROLE %I SET idle_in_transaction_session_timeout = %L', :'db', '30s') \gexec
SQL
  # The schema itself, owned by the service role, inside the service's database.
  psql -v ON_ERROR_STOP=1 -d "$db" -v db="$db" -c "CREATE SCHEMA IF NOT EXISTS \"$db\" AUTHORIZATION \"$db\""
done
