# HILTECH — isolated synthetic-data Coolify staging

**Status: implementation candidate, not deployed.** This is a two-file Coolify Git-compose topology, based on the verified immutable server image. `compose.yaml` contains dependencies only; `compose.api.yaml` contains the explicitly activated Spring API. This separation allows OpenFGA store/model IDs and Keycloak issuer to be provisioned *after* the backing services start. Never use the development `infrastructure/local/docker-compose.yml` on any public host.

## What it contains

- PostgreSQL 18.6 with one private persistent volume, **three separate database owners and databases** (application / Keycloak / OpenFGA); no host-exposed DB port.
- Keycloak `start` (not `start-dev`) configured for TLS termination at an existing Coolify HTTPS proxy; OpenFGA private, with its migrations and playground OFF.
- An explicitly opt-in `api` profile, with OIDC + OpenFGA + evidence integrations **ON**, required settings fail-closed, and user approval–dependent field feature flags OFF.
- Resource limits for an initial sizing exercise, **not a promise that two 1-vCPU shared servers can host the full stack**.

Evidence uses a **separately provisioned private S3-compatible endpoint/bucket**. Do not substitute the local Moto simulator. Presigned upload/download endpoints must resolve via HTTPS on actual Android and Windows devices. This avoids pretending a dummy fifth container fulfills the evidence-storage contract.

## Hosting and protections

This manifest is NOT permission to add services to the existing busy hosts. The Coolify control plane already has a `HILTECH Internal OS` project and empty `staging` environment; use that project only. `teswa-core01` currently runs a healthy Teswa service with several components and is **not** verified spare. Neither existing 1-vCPU host has established headroom for the stack. Prefer a distinct execution VM registered under the SAME existing Coolify, initially **at least about 2 vCPU / 8 GiB RAM**, subject to real load tests. Never provision a paid VM without the owner's approval.

No existing service stop, restart, cleanup, delete, database reuse or volume reuse. Do not move real company, payroll or warehouse data into this staging. Confirm an encrypted off-host backup destination, perform a **disposable restore** and establish rollback before any actual pilot.

## Deployment sequence

1. **Capacity/access:** select an actually suitable host, check disk/CPU/RAM, Docker/proxy network, TLS routes, registry pull permissions and egress. Confirm host cost and owner authorization if new compute is needed. Do not host this on the existing servers merely because the API image exists.
2. **Compose checkout:** create a dedicated resource in existing HILTECH `staging`. Stage A uses `infrastructure/coolify/staging/compose.yaml`; Stage B combines it with `infrastructure/coolify/staging/compose.api.yaml`. Confirm the target Coolify Git Compose implementation supports multiple files (or use an explicit approved deployment method with both files); do not assume switching a UI selector automatically enables the API. Confirm Coolify resolves the bind-mounted `./postgres-init.sh` relative to the base compose directory. Supply `HILTECH_COMPOSE_PROJECT_NAME` and real `HILTECH_COOLIFY_PROXY_NETWORK` of the selected host.
3. **Secrets:** set dependency variables in Coolify for Stage A; introduce additional required API variables in Stage B after Keycloak realm and FGA store/model exist. Store real `${VARIABLE:?}` entries **in Coolify's private configuration**, never in Git or chat. Separate PostgreSQL admin, app, Keycloak, OpenFGA, Keycloak bootstrap, S3 and cursor-signing credentials. `HILTECH_OPENFGA_DATABASE_URI` must identify the same OpenFGA role/database/password as PG initialization; URL-encode password special characters. **Never print `docker compose config` after real credentials are injected**: it expands secrets into output.
4. **Dependencies first:** using **only the base file**, run `docker compose -f infrastructure/coolify/staging/compose.yaml up -d` (or an equivalent approved Coolify action). This does not parse or require the still-unknown API store/model settings. Check Postgres DB/role initialization, Keycloak TLS/realm and OpenFGA private service. No public PostgreSQL/OpenFGA routes. GitHub CI packaging checks do not replace these startup checks.
5. **Provision identity/authorization:** create the real Keycloak realm, native OIDC clients with exact Windows/Android redirect allowlist, proper issuer/audience and authorization roles. Rotate the bootstrap admin. Create an OpenFGA store and apply the reviewed `docs/13-delivery/first-slice-contract-pack/openfga/first-slice-model.fga` or approved successor. Supply real store/model IDs. The Compose intentionally **does not** guess or fabricate these identities.
6. **Private evidence:** set an HTTPS S3-compatible endpoint, staging-only bucket, scoped credentials and required CORS, object integrity/AV and retention/recovery. Confirm that presigned URLs are accessible to native clients. Validate private object access and backup/restore.
7. **Explicit API activation:** combine both Compose files using `docker compose -f infrastructure/coolify/staging/compose.yaml -f infrastructure/coolify/staging/compose.api.yaml --profile api up -d` (or a verified equivalent in Coolify); then start `hiltech-api` with the immutable GHCR image. Probe `GET /actuator/health/liveness`, `GET /actuator/health/readiness`, and confirm `/actuator/env` is denied. Readiness tests PostgreSQL, **not** all external dependencies.
8. **Operator smoke:** synthetic first tenant, authentication, OpenFGA denial/allow checks, evidence READY, stock movement/QR/work assignment, offline replay, role scoping and rollback. Then internal Android debug APK + Windows candidate using staging configuration and actual-device checks. Keep material usage reconciliation and finalization feature flags OFF until owner/policy approval.

## Deployment provenance and explicit limitation

Published immutable image: `ghcr.io/omarkhair70-droid/hiltech-server@sha256:6bf83b62746cf6811a610e0f59e9f35469a9fe4b7061bc01debe48c1d8e4d1e8`, source `fae1d7f36eb9adeaecfea6ccbd07baba6b2af646` (verified successful release workflow `45037178956`).

The stack is a **candidate template** requiring runtime/route/secret review. It has not yet been deployed, the target execution host is unresolved, and **zero HILTECH containers currently run in Coolify**. Track runtime approval in [issue #133](https://github.com/omarkhair70-droid/hiltech/issues/133).

To perform static safety checking with no secrets or Docker daemon:

```bash
python scripts/devops/verify_coolify_staging_template.py
```
