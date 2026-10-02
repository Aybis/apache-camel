# Local per-service environment

Put `<service>.env` files here (for example `payment-gateway.env`) to give one service its own
secrets in the local Docker Compose stack: credentials, client IDs and secrets, keys. `*.env` files in this
folder are git-ignored; never commit them. Connection settings (partner endpoints, hosts, queue managers)
do not go here: they live in Git, in the service's `application.yml` (CLAUDE.md rule 8). Production gets the same variables from Kubernetes Secrets
or the vault, not from files.

A service's own dev tooling may instead write `services/<service>/dev/.keys/dev.env`; that file is
picked up too. Both are optional. Regenerate the compose file after adding a service:
`bash scripts/gen-compose.sh`.
