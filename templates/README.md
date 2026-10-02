# templates

Source files that scripts copy to create new things. Nothing here is built or run on its own.

| Folder | Used by | Produces |
|---|---|---|
| `service/` | `scripts/new-service.sh` | A new `services/<name>/` folder: `pom.xml`, `Application`, `<Class>Routes`, a Camel test and `application.yml` |

The script replaces placeholders in two places:

- **File and folder names:** `__PACKAGE_PATH__` and `__CLASS__`.
- **File contents:** `__SERVICE__`, `__PACKAGE__`, `__CLASS__`, `__DOMAIN__`, `__PORT__`, `__DESCRIPTION__`.
  `__PACKAGE_PATH__` is not expanded inside files.

**ACE equivalent:** a project template in the Toolkit.

## Cautions

- Every file inside `service/` is copied into every new service. That is why this README sits one level
  up: a file added to `service/` appears in all future services.
- A change here affects only services created afterwards. Existing services are not updated; apply the
  same change to them by hand in the same pull request if it matters.
- Test a template change by generating a throwaway service and running `mvn -B verify`, then delete it
  (and revert `services/pom.xml`, `config/services.yml` and the generated Compose file).
