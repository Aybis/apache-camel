# services

One folder per integration service. Each folder is a Maven module, produces one jar and one container
image, and is deployed and scaled on its own.

**ACE equivalent:** each folder is one ACE application; each running container is one integration server.

| Service | Domain | Port | Purpose |
|---|---|---|---|
| [sample-service](sample-service/README.md) | demo | 8101 | Reference implementation of the conventions; simulates traffic |
| [payment-gateway](payment-gateway/README.md) | payments | 8102 | Bank-neutral payment API with one adapter per bank (BNI first) |
| [bank-simulator](bank-simulator/README.md) | payments | 8103 | Local SNAP BI bank for development and tests; never deployed to production |

The authoritative list is `config/services.yml`; this table is for reading convenience.

## Adding a service

Never create a folder here by hand. Use the script, which also registers the module, the port and the
Compose entry:

```bash
bash scripts/new-service.sh order-sync --domain orders --description "Syncs orders from SAP to WMS"
```

Then implement the routes, add a `README.md` for the new folder (see `sample-service/README.md` for the
expected sections), and add the service to the table above and to `docs/repository-map.md`.

## Building and testing

```bash
mvn -B verify                                   # everything, from the repository root
mvn -B -pl services/<name> -am verify           # one service and what it depends on
```

`services/pom.xml` is the parent of all service modules; its module list is maintained by the script.
