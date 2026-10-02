# deploy/postgres

Local PostgreSQL setup for the Docker Compose stack.

- `provision.sh` runs as the one-shot `db-provision` service in `deploy/docker-compose.yml` on every
  `docker compose up` / `scripts/stack.sh up`. It creates any missing database and role for the console
  and for every service marked `database: true` in `config/services.yml` (name `order-sync` -> `order_sync`).
  It never drops or changes existing ones.
- Local passwords: `POSTGRES_PASSWORD` (admin user `postgres`) and `SERVICE_DB_PASSWORD` (all service roles).
  `scripts/stack.sh up` generates both, randomly, into git-ignored `deploy/.env` on first run; Compose reads that
  file. Changing `SERVICE_DB_PASSWORD` is applied to existing roles on the next start. `POSTGRES_PASSWORD` only
  takes effect when the data volume is first created; provisioning connects over the local socket, so it
  works with any admin password. Port 5432 is bound to
  127.0.0.1 only. Local only; shared environments get a managed PostgreSQL with a
  separate secret per service.
- `max_connections` is 300 locally (default 100 is too few for 20 services with pools of 10).
- Data lives in the `postgres-data` volume; `scripts/stack.sh reset` deletes it.

Rules for services using a database: CLAUDE.md rule 11.
