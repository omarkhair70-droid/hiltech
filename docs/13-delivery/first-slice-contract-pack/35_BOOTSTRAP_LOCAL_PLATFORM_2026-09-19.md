# 35 — Bootstrap Local Platform

Date: 2026-09-19
Status: **PASS / BOOTSTRAP VERIFIED**

## Purpose

Provide a production-shaped local/development dependency stack for the frozen first-slice architecture without confusing local runtime conveniences with OCI production deployment.

## Implementation

Primary commits:
- `f689f37e4d60692c1169315bd0ce5503dd8ac0e1` — isolated local platform services,
- `225c37d5c0c4e4443831f715edc07d9e49afb584` — local platform contract gate,
- `529f4bec318af4c6ee0e98d0ca371e433ac2c492` — PostgreSQL 18 container data-layout correction.

Local services:
- PostgreSQL 18.6,
- Keycloak 26.7.4,
- OpenFGA 1.20.0,
- OpenFGA CLI 0.7.20 helper,
- Moto 5.2.3 disposable S3-compatible endpoint.

## Database isolation

The local PostgreSQL service creates isolated logical databases/users:
- `hiltech` → `hiltech_app`,
- `hiltech_keycloak` → `hiltech_keycloak`,
- `hiltech_openfga` → `hiltech_openfga`.

The contract test proves the Keycloak/OpenFGA roles cannot read HILTECH business tables.

## Identity / authorization

The safe local Keycloak realm imports the frozen public native client:
- Authorization Code,
- PKCE S256,
- no Direct Access Grant,
- Android private-use redirect,
- Windows loopback redirect.

The OpenFGA helper loads the canonical frozen `.fga` model; no duplicate hand-maintained local model is introduced.

## PostgreSQL 18 correction

The first smoke exposed the PostgreSQL 18 Docker image layout change. The volume mount was corrected from `/var/lib/postgresql/data` to the PostgreSQL 18-compatible `/var/lib/postgresql` root.

This was a Bootstrap runtime contradiction, not a product-contract change.

## Verification

Canonical run:
`35410673449` (#30) — **SUCCESS**

It proved:
- Compose validation,
- all local services start,
- Keycloak discovery,
- OpenFGA health,
- canonical OpenFGA model load,
- HILTECH Flyway migration on the local app database,
- Keycloak/OpenFGA database-user isolation,
- cleanup/reset path,
- all existing foundation/database/evidence regressions remain green.

## Boundary

Keycloak `start-dev`, local passwords and Moto are development-only.
Production remains OCI/private-network/TLS/KMS/Secrets under the frozen infrastructure contract.
