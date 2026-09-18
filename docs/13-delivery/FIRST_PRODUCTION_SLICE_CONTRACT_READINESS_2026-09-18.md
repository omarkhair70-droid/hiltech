# HILTECH First Production Slice — Contract Readiness

Date: 2026-09-18
Status: **PRE-FREEZE / TECHNICAL GATE CLOSED / BUSINESS CONTRACTS NOT YET FROZEN**

## Purpose

Turn the post-SPIKE-15 state into an implementation-readiness map.

The first production slice candidate remains:

PM Desktop
→ Project / Site / Work Order
→ Warehouse asset reservation / checkout
→ Technician Android job bundle
→ offline execution + evidence
→ reconnect / sync
→ Supervisor acceptance
→ PM authoritative read model
→ audit / activity

SPIKE-15 proves the hardest shared architecture path.
It does **not** authorize freezing real HILTECH business fields/policies that have not been validated.

## Configuration Principle

The product must freeze typed/versioned **configuration mechanisms**, not today's mutable company answers.

Examples that should be configurable:
- WorkTypes,
- readiness/evidence/review policies,
- approval routing,
- crew/assignment rules,
- tracking policy,
- warehouse/site storage locations,
- asset/stock categories,
- role/team/delegation relationships.

Reality provides model validation and initial seed values. It is not a reason to hard-code Mohamed, a current technician count, one evidence checklist, or one permanent warehouse structure.

---

# Status Legend

## PROVEN / BUILD-READY FOUNDATION
The architecture/semantics are sufficiently proven to implement once production bootstrap begins.

## TECHNICALLY READY / REALITY-BLOCKED
The implementation pattern is known, but exact HILTECH business fields/policy/authority must be validated before schema/API freeze.

## DESIGN-BLOCKED
Domain/technical contract may be strong, but representative UX/navigation/conflict state still needs visual validation.

## PROVIDER/OPS-BLOCKED
Protocol/architecture is known, but production provider/operational choice remains open.

---

# 1. Shared Technical Foundation

Status: **PROVEN / BUILD-READY FOUNDATION**

Accepted:
- Kotlin Multiplatform + Compose Multiplatform.
- Android + JVM Desktop shared client architecture.
- Spring Boot + Spring Modulith modular monolith.
- PostgreSQL authoritative transactional store.
- jOOQ persistence access.
- Room3/SQLite local store.
- Ktor Client 3.5.2 shared networking — ADR-007.
- Android OkHttp Ktor engine.
- JVM Desktop CIO Ktor engine.
- Keycloak native OIDC / PKCE.
- OpenFGA object/action authorization.
- WorkManager constrained reconnect/background execution.
- S3-compatible binary evidence protocol.
- W3C trace / correlation contract.
- GitHub Actions CI control plane.
- Windows MSI installer-swap operational baseline.

Evidence:
- SPIKE-01 through SPIKE-15 accepted.
- final E2E run 35323209954.
- same-head Room/offline/WorkManager regressions green.

No further platform feasibility spike is required before production bootstrap.

---

# 2. Cross-Cutting HTTP / Command Contract

Status: **PROVEN FOUNDATION / DOMAIN PAYLOADS NOT FROZEN**

Validated wire semantics:
- Bearer OIDC access token.
- `X-Correlation-Id`.
- W3C `traceparent`.
- `Idempotency-Key`.
- `baseVersion` in command body for concurrency-sensitive commands.
- explicit command endpoints, not generic unrestricted CRUD.
- duplicate replay returns semantic duplicate/applied result without repeated side effect.
- stale version returns explicit conflict.
- offline queue preserves local intent/evidence.
- dependent commands can become BLOCKED_BY_CONFLICT.
- S3 upload reservation/direct upload/finalization pattern.

Still required before first-slice API freeze:
- exact endpoint/resource names,
- exact request/response fields,
- exact error envelope per first-slice command,
- cursor/read-model schema where needed,
- REAUTH_REQUIRED representative command,
- retryable HTTP/network error mapping,
- object-hidden vs permission-denied external policy.

---

# 3. Identity / Organization / Session

Status: **TECHNICALLY READY / REALITY-BLOCKED**

Architecture is decided:
- Keycloak owns authentication/session identity.
- HILTECH UserIdentity links auth subject to product identity.
- OpenFGA relationships own object/action authorization.
- role labels alone are not authorization truth.
- revoked identity/device/session cannot authorize privileged work.

Still requires HILTECH validation:
- actual employee/user population source,
- exact organization/team structure,
- real role combinations,
- Mohamed/Ahmed/PM/Supervisor authority relationships,
- delegation rules,
- device ownership/trust policy,
- whether Device/Session business-side records are required in first release and at what detail.

Do not freeze exact membership/role seed data before this validation.

---

# 4. Project / Site

Status: **TECHNICALLY READY / REALITY-BLOCKED**

Strong model exists for:
- Project,
- Site,
- Area/Room/Zone,
- Milestone,
- WorkPackage,
- lifecycle/version/audit concepts.

Known invariants:
- projectCode unique,
- baseline changes after activation are controlled/versioned,
- project history survives closure,
- site access/location fields are permission-scoped,
- critical lifecycle transitions are online authoritative.

Reality blockers:
- actual project creation source,
- real project/site hierarchy,
- HILTECH naming/code conventions,
- whether one reusable client site can span projects,
- actual PM assignment rule,
- kickoff/ready/active/handover/close criteria,
- which client acceptance artifact is authoritative.

No exact Project/Site migration may be frozen until one real project is walked end to end.

---

# 5. Work Order

Status: **HIGHEST FIRST-SLICE READINESS / BUSINESS POLICY STILL BLOCKED**

Technically proven:
- create/assign online authoritative path,
- durable Android job bundle,
- Room local storage,
- StartWork offline command,
- completion submission offline,
- process-death survival,
- WorkManager reconnect/replay,
- evidence upload/finalize,
- operationId idempotency,
- baseVersion conflict,
- dependent-command blocking,
- supervisor exact-version acceptance,
- PM authoritative read/audit.

Strong domain model already exists for:
- WorkOrder identity/context,
- assignment,
- lifecycle state,
- readiness state,
- evidence policy reference,
- drawing/material/asset requirements,
- instruction version,
- optimistic version.

Strong transition model already exists for:
- DRAFT → PLANNED,
- readiness evaluation,
- READY → ASSIGNED,
- ASSIGNED → IN_PROGRESS,
- block/resume,
- submit completion,
- accept/rework,
- close/cancel.

Before schema/API freeze:
- define a typed WorkTypeDefinition schema,
- define versioned ReadinessPolicy,
- define versioned EvidencePolicy,
- define versioned ReviewPolicy,
- define assignment/crew policy,
- define configurable instruction/checklist templates,
- ensure field terminology/codes can be configured or seeded without schema change.

A representative real job validates those schemas and supplies initial configuration. The current HILTECH reviewer, evidence checklist, or crew makeup is not itself a code blocker.

---

# 6. Asset / Warehouse Custody

Status: **TECHNICALLY STRONG / REALITY-BLOCKED**

Proven architecture:
- authoritative version/concurrency pattern,
- one-winner collision handling,
- idempotent operationId,
- append-only movement/ledger thesis,
- no silent negative stock pattern,
- QR/tag identity architecture,
- asset/current-custody as projection from movements rather than free-form edits.

Strong domain model exists for:
- Asset,
- AssetTag,
- AssetMovement,
- AssetIncident,
- CalibrationRecord,
- Warehouse,
- StorageLocation,
- StockItem,
- StockBalance,
- StockMovement,
- Reservation,
- Stocktake,
- ReceivingRecord.

Strong command model exists for:
- ReserveAsset,
- CheckoutAsset,
- ReturnAsset,
- TransferAsset,
- ReportAssetDamage/Missing,
- stock receive/reserve/issue/consume/return/count/adjust.

Before schema freeze:
- storage-location model must support Main Warehouse + Project/Site temporary storage,
- asset/stock categories and units must be configurable master data,
- serialized-vs-quantity rules must be configurable by item/category where safe,
- calibration requirement must be configurable by asset type,
- custody/checkout/return invariants remain hard domain rules,
- offline final checkout remains a policy/architecture decision only if HILTECH truly needs it.

A warehouse walkthrough validates the model and seeds initial locations/categories; it should not force permanent hard-coded warehouse/category lists.

---

# 7. Evidence / Documents

Status: **PROVEN PROTOCOL / POLICY + PROVIDER BLOCKED**

Proven:
- local durable evidence,
- direct S3-compatible signed upload,
- checksum identity,
- finalize only after stored-byte verification,
- corrupt/truncated upload cannot become READY,
- retry succeeds,
- evidence can participate in offline work flow.

Still required:
- production object-storage provider,
- real evidence classes/size distribution,
- retention/legal policy,
- document/evidence metadata fields,
- per-work-type evidence requirements,
- restricted sharing rules,
- multipart threshold if needed.

---

# 8. Offline / Local Schema

Status: **PROVEN SEMANTICS / EXACT DOMAIN CACHE NOT FROZEN**

Proven:
- Room3/SQLite,
- durable PendingCommand,
- operation ordering,
- retry/idempotency,
- conflict + dependent blocking,
- WorkManager reconnect,
- process death,
- user-observable sync state.

Can be frozen now at semantic level:
- queue-state meanings,
- operationId stability,
- baseVersion usage,
- local intent/evidence preservation,
- server remains authoritative.

Still requires first-slice schema freeze:
- exact cached Project/Site/WorkOrder fields,
- exact job bundle schema,
- evidence queue metadata,
- sync cursor/read-model cache,
- local migration/version policy.

---

# 9. Authorization

Status: **ENGINE READY / REAL AUTHORITY BLOCKED**

Proven:
- OpenFGA can express HILTECH-shaped PM/technician/supervisor/object relationships.
- server enforces allow/deny.
- unrelated actor is denied.
- field-level policy remains application/server responsibility.

Already modeled:
- Project object/action permissions,
- WorkOrder object/action permissions,
- Asset/Stock permissions,
- field-level Project/Site/Asset visibility.

Reality blockers:
- exact Mohamed/PM/Supervisor/Engineer/Warehouse authority,
- temporary delegation policy,
- client visibility,
- threshold/approval obligations,
- re-auth requirements,
- device-trust obligations.

OpenFGA schema can be structured now, but production relationship/policy tests cannot be frozen until authority validation.

---

# 10. First-Slice UX

Status: **DESIGN-BLOCKED / SPECS READY**

Existing low-fi:
- Technician Today,
- Warehouse Scan,
- Project Command Center.

Specified but visual build/validation still pending:
- Technician Job Detail,
- Warehouse Checkout,
- RTL variants,
- offline conflict state,
- asset-already-checked-out state,
- technician/warehouse tablet variants,
- navigation comparison.

Figma quota is a tooling blocker, not a product/architecture blocker.

Production UI should not begin before these representative first-slice states are visually validated.

---

# 11. Production Operations

Status: **PROVIDER/OPS-BLOCKED**

Still open:
- infrastructure/hosting provider,
- production object-storage provider,
- observability backend/collector,
- production Windows code-signing certificate/provider,
- enterprise distribution/updater,
- backup/RPO/RTO,
- exact Flyway baseline,
- final server/container/runtime pins,
- final CI action pinning/version re-check.

These do not reopen the core architecture decision.

---

# Exact First-Slice Freeze Order

When reality evidence is available, freeze in this order:

1. Identity subject/org/team/authority subset needed by the slice.
2. Project + Site exact identity/context fields.
3. WorkOrder exact fields/states/commands/read models.
4. Asset + Warehouse custody subset needed by the slice.
5. Evidence policy + metadata.
6. OpenFGA relationships + application obligations.
7. API request/response/error schemas.
8. PostgreSQL tables/constraints/indexes + Flyway migrations.
9. Room job-bundle/local-command/evidence schemas.
10. Ktor repositories/transport mappings.
11. PM / Warehouse / Technician / Supervisor representative UI contracts.
12. contract/integration/authorization/offline tests.
13. Freeze Review.

---

# What Can Be Generated Immediately After Freeze

Once the above exact contracts are approved, repository bootstrap should be mechanical enough to generate/implement:

- Gradle roots/version catalog/convention plugins,
- Android/Desktop/server modules,
- PostgreSQL/Flyway migrations,
- jOOQ generation,
- Spring module boundaries,
- typed command/query DTOs,
- Ktor shared client,
- OpenFGA model/tests,
- Keycloak dev config,
- Room entities/DAOs/migrations,
- WorkManager sync orchestration,
- S3 evidence client/server contracts,
- CI workflows,
- test fixtures,
- observability wiring,
- first vertical slice UI.

That is the point where production becomes the intended **implementation machine**, not another discovery phase.

---

# Current Decision

**DO NOT START PRODUCTION CODE YET.**

Reason is no longer unresolved technical architecture.

Remaining blockers are:
- real HILTECH workflow/authority/warehouse/field facts,
- exact first-slice domain contracts,
- representative design validation,
- production provider/ops choices required for freeze.
