# sample-service

The reference service. It exists to show the platform conventions in working code and to give the
dashboards and log views data straight after `bash scripts/stack.sh up`. It has no business purpose and
must not be deployed outside local or test environments.

**ACE equivalent:** a sample application, comparable to the samples shipped with the ACE Toolkit.

## What it does

| Route id | Behaviour |
|---|---|
| `sample-service-ping` | `GET /api/sample-service/ping` returns `{"service":"sample-service","status":"ok"}` |
| `sample-service-orders` | A timer creates a simulated order every 5 s (`sample.order-interval`); about one in ten fails, exercising redelivery and the dead letter channel |

## What to copy from it

- Routes extend `RouteBuilder`, are Spring `@Component`s, and use route ids `<service>-<purpose>`.
- Business events through `PlatformLog.event(...)`, which land in `event.action` and `labels.*` in Loki.
- The Log EIP receives the class logger, so console log-level changes for the package apply.
- No logging, error-handling or metrics configuration in the service: the starter supplies it.
- A Camel test (`SampleServiceRoutesTest`) with the console disabled.

## Run and test

```bash
mvn -B -pl services/sample-service -am verify
java -jar services/sample-service/target/sample-service.jar     # http://localhost:8101/api/sample-service/ping
```

Or run it with everything else through `bash scripts/stack.sh up`.
