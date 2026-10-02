# Database overlay for new services

`scripts/new-service.sh <name> --database` copies `templates/service` and then this folder on top of it
(files here replace or add to the base template), inserts `pom-dependencies.xml` into the service pom,
and sets `database: true` in `config/services.yml`. This README and `pom-dependencies.xml` are not copied.
