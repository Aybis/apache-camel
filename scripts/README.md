# scripts

Command-line tools for working with the repository. Run them from any directory; they locate the
repository root themselves.

| Script | Purpose |
|---|---|
| `new-service.sh <name> --domain <domain> --description "..."` | Creates `services/<name>/` from `templates/service`, adds the module to `services/pom.xml`, registers the service with the next free port in `config/services.yml`, and regenerates the Compose file |
| `gen-compose.sh` | Regenerates `deploy/docker-compose.services.yml` from `config/services.yml` |
| `stack.sh up \| down \| reset \| logs <service>` | Builds the jars and images and runs the local stack in `deploy/` |

Bank-specific scaffolding lives with the service that owns it: `services/payment-gateway/new-bank.sh`.

**ACE equivalent:** roughly the role of `mqsicreatebar`, `mqsideploy` and Toolkit wizards, for the local
environment only.

## Requirements

Bash, Java 25 JDK, Maven 3.9+, Docker with Compose v2.

## Cautions

- `new-service.sh` edits shared files (`services/pom.xml`, `config/services.yml`, the Compose file). Commit
  its output together with the new service, and rebase carefully when two services are added in parallel:
  both may pick the same port.
