# 38 — Repository Bootstrap Verification Decision

Date: 2026-09-19
Decision: **PASS**

`REPOSITORY_BOOTSTRAP_VERIFICATION = PASS`

`PHASE_0_ENGINEERING_FOUNDATION = VERIFIED`

## Scope

This decision closes the post-Freeze Repository Bootstrap gate defined by:
`17_FREEZE_TO_BOOTSTRAP_BOUNDARY.md`.

It does not declare the HILTECH business operating system complete.
It declares the shared engineering foundation safe to merge and use for Phase 1 / subsequent vertical implementation.

## Verified foundation

The Bootstrap now contains and verifies:

1. Gradle multi-project / version catalog / convention foundation.
2. Android and Windows/Desktop application shells.
3. Spring Boot / Spring Modulith server skeleton and module ownership markers.
4. PostgreSQL 18.6 + Flyway V0001–V0009.
5. deterministic jOOQ Kotlin generation.
6. shared DTO/error/network contract foundation.
7. Ktor authenticated/idempotent command transport.
8. Room schema v1 / DAOs / schema history.
9. durable offline replay + conflict/dependency semantics.
10. Android WorkManager sync runtime.
11. Keycloak OIDC resource-server boundary.
12. pinned-model OpenFGA decision/projection/outbox/fail-closed boundary.
13. signed private Evidence object-storage boundary.
14. isolated local/dev platform services.
15. safe OpenTelemetry / W3C / OTLP foundation.
16. OCI Terraform contract roots/modules.
17. CI regression, IaC and supply-chain gates.
18. contract/integration/offline/security evidence.

## Evidence ledger

Canonical implementation records:
- 25 — Flyway order change control,
- 26 — identity/organization lifecycle closure,
- 27 — jOOQ generation,
- 28 — Room/offline schema,
- 29 — durable command replay,
- 30 — Ktor authenticated transport,
- 31 — Android sync runtime,
- 32 — Keycloak/OIDC server,
- 33 — OpenFGA projection,
- 34 — Evidence object storage,
- 35 — local platform,
- 36 — observability,
- 37 — OCI IaC / CI / supply chain.

## Canonical comprehensive verification

Branch:
`bootstrap/phase0-20260919`

Implementation head before this decision:
`ce867b04eb6c3720f214e8a21859594741d4312d`

GitHub Actions:
`35411127169` (#35) — **SUCCESS**

Green jobs:
- foundation,
- database-contract,
- local-platform-contract,
- evidence-storage-contract,
- terraform-contract,
- supply-chain-contract.

The PR-only dependency-review job is intentionally skipped on branch push.
On the Bootstrap PR it resolves only shipped runtime dependency graphs and must pass the OSV HIGH/CRITICAL vulnerability gate before merge.

The first shipped-runtime review identified Tomcat 11.0.24 as the sole HIGH/CRITICAL runtime blocker. Bootstrap security change control patches the embedded Tomcat 11.0 line to **11.0.26** while retaining Spring Boot 4.1.1.

## Change-control result

No frozen business/product contract contradiction requiring reopening the first-slice Freeze was found.

Implementation-specific corrections were handled explicitly, including:
- Flyway dependency order,
- deterministic generated-source verification,
- PostgreSQL 18 Docker data-layout correction.

No silent redesign was accepted.

## Merge gate

Repository Bootstrap may be merged to `main` only after:
1. final decision/status commit regression is green,
2. Bootstrap PR is open against the unchanged frozen main,
3. PR-specific resolved-dependency OSV review executes and passes,
4. all other PR jobs remain green,
5. PR head has not moved at merge time.

## Next after merge

**PHASE 1 — Identity, Organization, Permissions Foundation**

Then continue the first production proof as complete vertical slices:
DB → domain/application → authorization/audit → API → client/local → UI/offline → tests → observability → staging smoke.

OCI cutover, DigiCert issuance, real-device/pilot evidence and later-domain reality remain their defined activation/later-phase gates; they do not reopen the verified Phase 0 architecture by default.
