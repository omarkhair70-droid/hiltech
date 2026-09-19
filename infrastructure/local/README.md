# HILTECH local platform

Status: **BOOTSTRAP local/dev runtime**

This directory runs the frozen external service contracts locally without pretending to be production infrastructure.

Services:
- PostgreSQL 18.6,
- Keycloak 26.7.4,
- OpenFGA 1.20.0,
- OpenFGA CLI 0.7.20 helper,
- Moto 5.2.3 S3-compatible disposable object-storage endpoint.

The HILTECH Spring server and native apps remain normal repository builds; this Compose file supplies their local external dependencies.

## Start

From repository root:

```bash
docker compose --env-file infrastructure/local/.env.example \
  -f infrastructure/local/docker-compose.yml up -d postgres keycloak openfga-migrate openfga moto
```

For personal local values, copy `.env.example` outside source control or to an ignored local file and change the development-only passwords.

## PostgreSQL ownership

One local PostgreSQL server hosts three isolated databases/users:

- `hiltech` owned by `hiltech_app`,
- `hiltech_keycloak` owned by `hiltech_keycloak`,
- `hiltech_openfga` owned by `hiltech_openfga`.

The separate owners intentionally mirror the production isolation contract. Keycloak/OpenFGA credentials do not grant access to HILTECH business tables.

Application JDBC default for this stack:

```text
jdbc:postgresql://127.0.0.1:5432/hiltech
user=hiltech_app
password=hiltech-app-local
```

Run Flyway through the normal server build; do not let a local service invent an alternate migration path.

## Keycloak

Local issuer:

```text
http://127.0.0.1:8081/realms/hiltech
```

The imported safe realm configuration creates the frozen public native client:

```text
client_id=hiltech-native
Authorization Code=true
PKCE=S256
Direct Access Grant=false
Android redirect=com.hiltech.app:/oauth2redirect
Windows loopback=http://127.0.0.1:*
```

No application user/password fixture is committed.

Useful local server configuration:

```text
HILTECH_OIDC_ENABLED=true
HILTECH_OIDC_ISSUER_URI=http://127.0.0.1:8081/realms/hiltech
```

Keycloak `start-dev` is **local-only**. Production uses the frozen OCI/private-network/TLS contract.

## OpenFGA

Local API:

```text
http://127.0.0.1:8082
```

Playground:

```text
http://127.0.0.1:3000
```

The database migration runs before the OpenFGA service.

Import the canonical frozen model + fixtures with the pinned CLI:

```bash
docker compose --env-file infrastructure/local/.env.example \
  -f infrastructure/local/docker-compose.yml \
  --profile tools run --rm openfga-cli \
  store import --file /contracts/first-slice-model.fga.yaml
```

The command prints the created store/model identifiers. Put those runtime values into:

```text
HILTECH_FGA_ENABLED=true
HILTECH_FGA_API_URL=http://127.0.0.1:8082
HILTECH_FGA_STORE_ID=<created store id>
HILTECH_FGA_MODEL_ID=<created authorization model id>
```

Do not create a second hand-maintained local authorization model.

## Object storage

Moto local endpoint:

```text
http://127.0.0.1:5000
```

It is disposable protocol infrastructure, not the production provider.

The production evidence adapter stays S3-compatible and production uses OCI Object Storage + KMS. For local contract verification, the Bootstrap evidence-storage CI/test creates the private bucket and proves:
- signed single PUT,
- expected SHA-256,
- retry after corrupt/truncated upload,
- authoritative finalization re-hash,
- private unsigned read denial,
- signed GET.

## Reset

To discard all local service state:

```bash
docker compose --env-file infrastructure/local/.env.example \
  -f infrastructure/local/docker-compose.yml down -v
```

Resetting local state is not a migration strategy and has no production equivalent.
