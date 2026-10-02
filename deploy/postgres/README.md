# deploy/postgres

Local PostgreSQL setup for the Docker Compose stack.

- `provision.sh` runs as the one-shot `db-provision` service in `deploy/docker-compose.yml` on every
  `docker compose up` / `scripts/stack.sh up`. It creates any missing database and role for the console
  and for every service marked `database: true` in `config/services.yml` (name `order-sync` -> `order_sync`).
  It never drops or changes existing ones.
- Local passwords: `POSTGRES_PASSWORD` (admin user `postgres`) and `SERVICE_DB_PASSWORD` (all service roles),
  both defaulting to `local-dev-only`. Local only; shared environments get a managed PostgreSQL with a
  separate secret per service.
- Data lives in the `postgres-data` volume; `scripts/stack.sh reset` deletes it.

Rules for services using a database: CLAUDE.md rule 11.
