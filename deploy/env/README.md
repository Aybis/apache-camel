# Local per-service environment

Put `<service>.env` files here (for example `payment-gateway.env`) to give one service its own
variables in the local Docker Compose stack: partner endpoints, client IDs, keys. `*.env` files in this
folder are git-ignored; never commit them. Production gets the same variables from Kubernetes Secrets
or the vault, not from files.

A service's own dev tooling may instead write `services/<service>/dev/.keys/dev.env`; that file is
picked up too. Both are optional. Regenerate the compose file after adding a service:
`bash scripts/gen-compose.sh`.
