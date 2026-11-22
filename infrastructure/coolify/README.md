# HILTECH server image — Coolify staging preparation

**State:** CI packaging proof only. **Not a production deployment** and not a complete operational stack.

This directory supports an eventual isolated HILTECH Staging application on the owner's existing Coolify control plane. The current live host is shared with many projects, and an approval gate for resource budget, backup/restore and dependencies remains open ([#133](https://github.com/omarkhair70-droid/hiltech/issues/133)).

## What is actually built

`Dockerfile.server` accepts a minimal build context containing **exactly one** previously verified Spring Boot `app.jar` and a copy of the repository's `database/migrations/` directory. These are assembled inside **GitHub Actions** by `.github/workflows/devops-hiltech-server-package.yml`, not on the 1-vCPU Oracle VM.

- Java 21 JRE is pinned to an inspected registry index digest.
- Runtime uses unprivileged UID/GID `10001:10001`; image exposes port 8080.
- `HILTECH_MIGRATIONS_PATH` points to bundled Flyway migrations at `/opt/hiltech/migrations`, matching `server/application/src/main/resources/application.yml`.
- JVM heap scaling uses `MaxRAMPercentage=70` **only if the container actually has a memory limit**. This does not itself set Docker resource limits.
- The workflow verifies exact source and JAR checksum, constructs the image, checks its nonroot user and Java runtime, and stores short-lived build metadata. The **CI-only image is not pushed to any registry or deployed**.
- `GITHUB_SHA` on pull_request runs can be a **test merge commit SHA**, not simply the PR head. Identify the exact validated run and provenance before a release.

## Before using on real Coolify

Do **not** make the server public or reuse `infrastructure/local/docker-compose.yml`; it includes development credentials and Keycloak `start-dev`. Any Staging rollout requires:

1. **Capacity + isolation approval**: verify limits for JVM, Keycloak, OpenFGA, PostgreSQL and storage; do not assume idle memory guarantees safe operation on the 1-vCPU shared server.
2. **Private staging services**: separate network, database/data owners, volumes and private credentials; expose only reviewed ingress and configured HTTPS.
3. **Complete auth configuration**: explicitly configure `HILTECH_OIDC_ENABLED=true`, a reviewed external Keycloak issuer + client/redirect, and `HILTECH_FGA_ENABLED=true` with correct private OpenFGA model/store IDs. CI deliberately tests packaging without claiming the staged identity controls are configured.
4. **Evidence/storage**: real private object storage and integrity validation, not disposable Moto, and no public unsigned object access.
5. **Recovery**: tested application/Keycloak/OpenFGA Postgres and evidence backup/restore, documented rollback and owner-approved RPO/RTO.
6. **Deploy provenance**: registry image pinned by immutable digest, manifest reviewed, secrets provisioned without putting values in Git/Coolify logs, then staged synthetic-data smoke checks.
7. **Clients**: independent Android/Windows install/update rollback gates; the server container does not replace native client distribution.

This directory intentionally has **no ready-to-run production compose file or Docker auto-deploy action**. Deployment is a separate change requiring review. See `docs/13-delivery/RELEASE_STRATEGY.md` and `docs/13-delivery/devops/COOLIFY_HOST_AUDIT_AND_STAGING_GATE_2026-11-22.md`.

## Production-shaped environment and whole-journey acceptance (2026-11-22)

The owner requires **one coherent production-shaped HILTECH experience**, not a backend-only demo. Before any Coolify deployment read:

- [Production-shaped topology, role-to-role E2E rehearsal and Phase7–13 progression](../../docs/13-delivery/devops/PRODUCTION_SHAPED_STAGING_AND_FULL_JOURNEY_GATE_2026-11-22.md).
- `staging-release-evidence.example.json`: **intentionally all PENDING** evidence ledger; copy it outside the repository for actual evidence (no credentials, secrets, customer data or PII).
- `../../scripts/devops/staging_release_gate.py`: read-only, fail-closed gate to prevent a human reviewer from conflating a successful container build with a ready environment. Example:

```bash
python3 scripts/devops/staging_release_gate.py \
  --manifest infrastructure/coolify/staging-release-evidence.example.json \
  --environment preprod
```

This example **must exit 1 (NO-GO)** until genuine evidence is recorded. The checker only validates the *form* of declarations and evidence references; it cannot establish that attached evidence is true or that an operator has approved deployment. An authorized reviewer must inspect every proof. It performs no deployment or network access.

A successfully booted Spring image **does not** imply production-shaped identity, persistent OpenFGA, real evidence storage, capacity, backups, health endpoints, native signed installers, or cross-role acceptance. Work on these independently and update the ledger with specific source SHA and immutable image digest. Production uses a **separate** ledger, isolated databases/credentials/object bucket and mandatory `preprod_acceptance_reference`.
