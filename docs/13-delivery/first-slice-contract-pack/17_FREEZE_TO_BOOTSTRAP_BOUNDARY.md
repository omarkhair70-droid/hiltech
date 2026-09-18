# 17 — Freeze → Bootstrap Boundary

Status: **CANONICAL PRE-CODE BOUNDARY**
Date: 2026-09-18

## Why this exists

HILTECH deliberately requires Product/Contract Freeze before production implementation.

Therefore the Freeze cannot logically require production artifacts that are only created by implementation.

The correct boundary is:

**PRE-CODE FREEZE**
→ exact contracts/generation rules/test specifications

then

**REPOSITORY BOOTSTRAP**
→ generated/implemented SQL/code/config/tests

then

**BOOTSTRAP VERIFICATION**
→ prove implementation matches the frozen contracts

---

# 1. Required BEFORE FIRST_SLICE_FREEZE

Must be exact enough that implementation does not invent business/product semantics:

- domain objects/states/invariants.
- configuration/policy schemas.
- DB table/constraint/index contract.
- Flyway naming/order/generation convention.
- jOOQ generation/type mapping.
- API routes/DTO/error/versioning contract.
- Room entity/queue/conflict/retention/migration rules.
- authorization/OpenFGA model.
- Postgres↔OpenFGA consistency contract.
- evidence/storage protocol.
- UI interaction/state/RTL/adaptive contract.
- permission/field projection contract.
- operational/provider choices needed to instantiate infrastructure.
- test matrix/specifications.
- representative reality/model-coverage evidence.
- final Freeze Record.

Executable model validation that does not require production code is encouraged and can be a pre-freeze proof:
- OpenFGA model tests.
- schema/static validators.
- generated documentation validation.
- design prototypes.

---

# 2. NOT required before Freeze because it IS production implementation

Do not block Freeze waiting for:

- final Flyway SQL files.
- generated jOOQ sources.
- Spring controllers/handlers.
- production Ktor repositories.
- Room entity Kotlin classes.
- Room migration Kotlin code.
- WorkManager production workers.
- production OpenFGA projector code.
- production API integration tests.
- production DB integration tests.
- UI production components.
- deployable Android/Windows production apps.

Those are created **from** the Freeze.

---

# 3. Required immediately AFTER Freeze — Bootstrap Gate

Repository bootstrap must mechanically create:

1. Gradle/monorepo roots and convention plugins.
2. server/client/module skeletons.
3. V0001.. initial Flyway SQL from DB contract.
4. jOOQ generation config.
5. shared DTOs/error envelopes.
6. Ktor client boundary.
7. Spring server command/query boundaries.
8. Room entities/DAOs/migrations.
9. WorkManager sync scaffolding.
10. Keycloak/OpenFGA integration adapters.
11. OpenFGA projection/outbox processor.
12. evidence storage adapters.
13. observability baseline.
14. CI workflows.
15. contract/integration/security/offline test skeletons.

---

# 4. Bootstrap Verification Gate

Before normal feature implementation expands beyond foundation, generated/implemented bootstrap must prove:

## Database
- empty DB migrates from V0001.
- supported migration fixture upgrades.
- DDL constraint tests pass.
- jOOQ generates/compiles.

## HTTP
- DTO serialization contract.
- error envelope.
- auth token/correlation.
- REAUTH_REQUIRED flow.
- cursor contract.
- representative 4xx/5xx mapping.

## Local/offline
- Room migration fixtures.
- pending command persistence.
- retry/conflict mapping.
- storage policy behavior.

## Authorization
- frozen OpenFGA tests stay green.
- Postgres projection/outbox tests.
- fail-closed grant/revoke.
- model ID pinned.

## Evidence
- reserve/upload/finalize.
- size limit.
- quarantine path where required.
- authorization/download.

If implementation cannot satisfy a frozen contract:
- stop,
- identify the contradiction,
- amend the contract through formal change control,
- do not silently redesign inside code.

---

# 5. Meaning of BUILD_READY

A first-slice capability is BUILD_READY pre-code when:
- the production contract is frozen,
- test obligations are specified,
- dependencies/providers required to instantiate it are decided.

It does not mean the implementation tests already pass before the implementation exists.

---

# Current decision

Use this file whenever a Freeze checklist item ambiguously asks for a production executable artifact before code.

**Contracts/tests-specs before Freeze. Executable implementation verification immediately after Freeze.**
