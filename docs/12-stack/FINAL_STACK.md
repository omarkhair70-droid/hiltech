# HILTECH OS — FINAL STACK

Date: 2026-09-18
Status: **FROZEN FOR FIRST PRODUCTION SLICE / PRE-CODE**

This file is the canonical first-slice technology baseline.

It does not mean every future HILTECH domain/provider/version can never change.
It means the first production slice can now be implemented without choosing fundamental platform libraries or infrastructure architecture during coding.

Evidence basis:
- SPIKE-01…15 accepted.
- final E2E architectural vertical run 35323209954 PASS.
- OpenFGA contract run 35331537375 PASS.
- AGP 9.3.2 compatibility run 35388858253 PASS.
- AGP 9.3.3 compatibility run 35389326629 PASS.
- final version review:
  `FINAL_STACK_VERSION_REVIEW_2026-09-18.md`.

---

# 1. Product/client architecture

## Language
Kotlin:
**2.4.20**

## Shared client
Kotlin Multiplatform + Compose Multiplatform.

Compose Multiplatform:
**1.11.1**

Deliberate non-upgrade:
1.12.0 is not adopted for the first slice because 1.11.1 is already HILTECH-proven across Android + Windows and no required fix justifies invalidating that proof.

## Android build

Android Gradle Plugin:
**9.3.3**

Evidence:
- focused validation run 35389326629 PASS.
- shared tests, Android debug, Desktop compile, Windows EXE/MSI all green.

Gradle:
**9.5.0**

Android:
- compileSdk 36
- targetSdk 36
- minSdk 23
- JDK 17 client build line

Production repository commits Gradle Wrapper rather than relying on a floating runner install.

## Android local/offline

Room3:
**3.0.3**

Bundled SQLite:
**2.7.1**

KSP:
**2.3.10**

kotlinx.coroutines:
**1.11.0**

WorkManager:
**2.11.2**

CameraX:
**1.6.2**

KSP 2.3.12 exists but is deliberately deferred because 2.3.10 is the accepted Room/KMP proof line and no first-slice blocker requires an upgrade.

## Networking

Ktor Client:
**3.5.2**

Android engine:
**OkHttp**

JVM Desktop engine:
**CIO**

Wire DTO serialization:
**kotlinx.serialization / JSON**

Shared networking contract:
- one typed Ktor client/repository boundary,
- UI/domain never performs raw screen-level HTTP,
- operationId / Idempotency-Key,
- baseVersion,
- correlation/trace,
- typed error/conflict/re-auth semantics.

ADR:
ADR-007.

---

# 2. Windows desktop

UI:
Compose Multiplatform Desktop 1.11.1.

Packaging:
jpackage / MSI.

Install/update/rollback:
controlled signed-installer swap.

Canonical:
- ADR-012,
- SPIKE-07.

Production signing:
**DigiCert OV Code Signing + DigiCert KeyLocker cloud HSM**

Distribution:
**HILTECH Update Service**

Update artifact:
- signed MSI,
- private OCI artifact storage,
- authenticated release manifest,
- SHA-256 verification,
- Authenticode verification,
- expected publisher verification,
- controlled rollout,
- retained compatible rollback installer.

Release channels:
- INTERNAL
- PILOT
- STABLE

MDM/Intune:
optional future adapter to the same signed MSI; not a baseline dependency.

Canonical:
- ADR-019,
- first-slice Windows release/update contract.

---

# 3. Server

Language:
Kotlin 2.4.20.

Java:
**21 LTS**

Framework:
**Spring Boot 4.1.1**

Modular monolith:
**Spring Modulith 2.1.1**

Architecture:
- one deployable modular monolith baseline,
- explicit module ownership,
- no cross-module direct table mutation,
- durable domain events/read models,
- no premature microservices.

Explicitly NOT baseline:
- Kafka/RabbitMQ,
- Temporal,
- Redis,
- OpenSearch/Elasticsearch,
- microservices.

Those require a measured/reality-backed trigger before adoption.

---

# 4. PostgreSQL / persistence

Database:
**PostgreSQL 18.6**

Provider:
**OCI Database with PostgreSQL**

Persistence access:
**jOOQ 3.21.8**

JDBC:
**pgJDBC 42.7.13**

Migrations:
**Flyway under the Spring Boot 4.1.1 managed dependency line**

Exact resolved Flyway transitive version is captured through the production Gradle dependency lock/report during repository bootstrap.

Do not override Spring Boot's Flyway version without a concrete compatibility reason.

## Flyway convention

Root:
`database/migrations`

One global ordered stream:

- V0001 platform
- V0002 configuration
- V0003 identity_organization
- V0004 projects_sites
- V0005 work
- V0006 warehouse_assets
- V0007 evidence_documents
- V0008 authorization_projection
- V0009 indexes_projections

Filename:
`VNNNN__module__description.sql`

No normal production `baselineOnMigrate`.

Schema evolution:
forward / expand-migrate-contract.

## jOOQ generation

Generator:
KotlinGenerator.

Generated path:
`server/build/generated-src/jooq/main`

Package:
`com.hiltech.server.generated.jooq`

Generated sources:
**not committed**.

Database type baseline:
- uuid → UUID
- timestamptz → OffsetDateTime persistence boundary → domain Instant
- numeric → BigDecimal
- jsonb → JSONB persistence boundary → typed Kotlin object
- finite states → varchar + CHECK rather than PostgreSQL enum types.

---

# 5. Identity

Identity provider:
**Keycloak 26.7.4**

Native auth:
Authorization Code + PKCE S256.

Native password/direct grant:
disabled.

`offline_access`:
not baseline for offline field work.

Offline business work persists typed local commands; it does not depend on indefinite OIDC refresh semantics.

ADR:
ADR-008.

---

# 6. Authorization

Authorization engine:
**OpenFGA 1.20.0**

First-slice model:
`docs/13-delivery/first-slice-contract-pack/openfga/first-slice-model.fga`

Contract CLI:
**OpenFGA CLI 0.7.20**

Model test:
run **35331537375** PASS.

Boundary:
- PostgreSQL = authoritative business relationship truth.
- OpenFGA = relationship authorization projection.
- application/domain policy = lifecycle/threshold/evidence/exact-version/re-auth/field obligations.

Consistency:
- transactional PostgreSQL projection intent/outbox,
- fail-closed grants/revokes,
- explicit authorization model ID,
- selective higher consistency,
- no first-slice application-level positive allow cache.

ADR:
ADR-009.

---

# 7. Evidence / object storage

Protocol:
S3-compatible private object storage.

Provider:
**OCI Object Storage**

Encryption:
**OCI KMS** production key baseline.

Evidence limits first slice:
- max 16 MiB per object,
- one signed PUT,
- no multipart.

Evidence flow:
local durable file
→ reserve
→ signed private upload
→ server verifies size + SHA-256
→ security scan/quarantine if policy requires
→ authoritative READY.

Download:
- INTERNAL/RESTRICTED: short-lived signed private URL max 5 minutes.
- HIGHLY_RESTRICTED: authenticated HILTECH API proxy.
- no permanent public URL.

Automated authoritative evidence deletion:
off by default until explicit RetentionPolicy exists.

ADR:
ADR-010.

---

# 8. Offline / sync

Authoritative server + local-first working client.

Android local DB:
Room3.

Pending command:
- stable operationId,
- baseVersion,
- typed payload,
- payloadVersion,
- explicit dependencies,
- durable state.

Conflict:
- explicit typed conflict,
- no universal last-write-wins,
- dependent commands may be BLOCKED_BY_CONFLICT,
- local evidence/intent remains.

Background:
WorkManager 2.11.2.

Retry:
30s → 1m → 2m → 4m → 8m → 15m cap, ±20% jitter.

Authorization:
re-evaluated on replay.

Local storage:
- app-private,
- device OS encryption baseline,
- no SQLCipher first slice,
- HIGHLY_RESTRICTED not offline cached by default.

ADR:
ADR-011.

---

# 9. Observability

Application instrumentation:
**OpenTelemetry Java 1.66.0**

Trace standard:
W3C Trace Context.

Collector:
OpenTelemetry Collector.

OCI backend baseline:
- OCI APM for traces,
- OCI Logging / Logging Analytics,
- OCI Monitoring/alarms where appropriate.

Application instrumentation remains vendor-neutral.

No sensitive business/evidence payload in telemetry.

ADR:
ADR-013.

---

# 10. Cloud infrastructure

Provider:
**Oracle Cloud Infrastructure**

ADR:
ADR-014.

Primary production candidate:
**Saudi Arabia West / Jeddah — me-jeddah-1**

Secondary DR candidate:
**Riyadh — me-riyadh-1**

Runtime:
- OCI Container Instances preferred.
- OCI Compute running same containers is fallback if regional/quota/operational fit requires it.

No Kubernetes/OKE baseline.

Registry:
OCI Container Registry.

Ingress:
OCI Load Balancer.

Secrets:
OCI Secret Management.

Keys:
OCI KMS.

IaC:
Terraform + OCI Terraform Provider.

Remote state/locking/execution baseline:
OCI Resource Manager.

Delivery control:
GitHub Actions.

Architecture remains provider-portable:
OCI-specific logic belongs in IaC/adapters/ops, not domain modules.

---

# 11. Backup / disaster recovery

PILOT:
- automated PostgreSQL backups,
- PITR,
- at least 10-day PITR window baseline,
- cross-region backup copy,
- recovery rehearsal,
- engineering recovery target ≤4h,
- backup-copy RPO target bounded/measured.

STABLE:
- Jeddah primary candidate,
- Riyadh PostgreSQL Warm Standby candidate,
- RPO enforcement 300 sec / 5 minutes,
- engineering service-recovery drill target ≤60 minutes.

These are engineering acceptance targets, not customer SLA promises.

---

# 12. CI / GitHub Actions

Control plane:
GitHub Actions.

Production external actions use immutable full commit SHAs.

## Checkout

`actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1`

## Java

`actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1`

## Gradle

`gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb # v6.3.0`

## OpenFGA model test

`openfga/action-openfga-test@e89aa8259796cd5ee5c1b1ae7d72c401029cb947 # v0.1.2`

Production policy:
- least workflow permissions,
- protected staging/production environments,
- no production secrets on untrusted PRs,
- weekly Dependabot GitHub Actions review PRs,
- new third-party action requires explicit full-SHA admission review.

Canonical:
`docs/13-delivery/first-slice-contract-pack/22_CI_SUPPLY_CHAIN_PINNING_CONTRACT.md`

---

# 13. Version review policy

This stack is intentionally **not “latest everything.”**

Deliberate first-slice freezes include:
- Compose 1.11.1 over current newer 1.12.0.
- KSP 2.3.10 over current newer 2.3.12.
- AGP 9.3.3 over newly released 9.4.0.

Reason:
proven compatibility and focused evidence are preferred to unnecessary pre-Freeze churn.

Future upgrades:
reviewed dependency maintenance after Bootstrap/first slice, or earlier only for security/correctness/required-feature trigger.

---

# 14. Provider/runtime values resolved at Bootstrap or cutover

The following are not unresolved product architecture:

- exact OCI CPU/RAM shapes,
- exact Terraform CLI/provider patch,
- exact OTel Collector image digest,
- exact Keycloak/OpenFGA container digest,
- exact Flyway transitive resolved version,
- OCI tenancy OCIDs,
- bucket/DB resource IDs,
- DigiCert certificate serial,
- TLS certificate identity,
- secret OCIDs.

Those are deployment/lock/provenance values generated after contract Freeze and before production activation.

They are recorded in:
- dependency locks,
- IaC locks,
- image digests,
- release manifests,
- secret references,
- deployment records.

---

# 15. First-slice stack decision

`FINAL_STACK_REVIEW = PASS`

`FIRST_SLICE_STACK = FROZEN`

The first production slice may not reopen these choices casually during implementation.

A change requires:
- concrete contradiction/security/correctness trigger,
- focused validation,
- ADR/change-control update,
- compatibility review against frozen contracts.

---

# 16. First-slice Freeze status

Rendered design proof:
**PASS**

Evidence:
- run `35396169606`,
- 37 / 37 captures,
- artifact digest `sha256:6988eb2f01613a0e89c72a74f90e5304ddc349b1d8bc9e49819ec5f0056ac1b3`.

First-slice Freeze:
`PASS`

Canonical decision:
`docs/13-delivery/first-slice-contract-pack/24_FIRST_SLICE_FREEZE_DECISION_2026-09-19.md`

Next:
`REPOSITORY_BOOTSTRAP`

No first-slice pre-code gate remains outside the stack.
