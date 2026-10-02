# platform

Shared code that every service depends on. It currently holds one module:

| Module | Purpose |
|---|---|
| [camel-platform-starter](camel-platform-starter/README.md) | The "global functions": structured logs, correlation ID, error handling, metrics, health, console client |

**ACE equivalent:** a shared library, deployed inside each application rather than to the integration server.

Only cross-cutting, technical concerns belong here. Business logic, mappings and partner-specific code stay
in the service that owns them, because every change here is a change to all services at once.
