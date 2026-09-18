# 10 — First-Slice Cross-Contract Consistency Review

Status: **ACTIVE CONTRACT REVIEW**
Date: 2026-09-18

## Purpose

Prevent contract drift between:

Configuration
→ Domain Objects
→ PostgreSQL/jOOQ
→ API/Ktor
→ Room/Offline
→ Authorization
→ UI
→ Tests

A first-slice contract is not freeze-ready merely because each file looks reasonable independently.

---

# 1. Configuration → WorkOrder

Canonical rule:

WorkOrder does not hard-code today's:
- approver,
- evidence checklist,
- crew shape,
- readiness checklist,
- tracking cadence.

It binds versioned configuration:

- WorkTypeDefinition
- AssignmentPolicy
- ReadinessPolicy
- EvidencePolicy
- ReviewPolicy
- FieldTrackingPolicy?
- typed templates where required

Required consistency:
- DB stores sufficient revision identity.
- API returns sufficient policy-derived requirement snapshot.
- Room bundle caches requirement instances + binding identity.
- UI renders policy-derived requirements.
- server re-evaluates authority/state on command replay.
- history remains explainable after policy supersession.

Status: **CONSISTENT / CONTRACT CANDIDATE**

---

# 2. WorkOrder concurrency

Canonical:
- server authoritative.
- mutable aggregate has Long version.
- concurrency-sensitive command sends baseVersion.
- stale command returns VERSION_CONFLICT / typed domain conflict.
- offline client never last-write-wins assignment/cancellation.

Required:
- PostgreSQL optimistic condition.
- command handler transaction.
- API error envelope.
- PendingCommand baseVersion.
- ConflictRecord.
- UI recovery state.
- integration tests.

Status: **CONSISTENT / SPIKE-15 PROVEN**

---

# 3. Idempotency

Canonical:
- retry-sensitive command receives stable operationId / Idempotency-Key.
- duplicate retry returns prior semantic outcome, not a repeated side effect.

Applies first slice to:
- StartWork
- BlockWork/ResumeWork
- SubmitWorkCompletion
- Evidence reserve/finalize where retry-sensitive
- ReserveAsset
- CheckoutAsset
- ReturnAsset
- configuration activation/supersession
- other mutation commands selected by exact API freeze

Required:
- API header/command identity.
- server idempotency store/handler behavior.
- DB uniqueness/operation result.
- Room PendingCommand stable operationId.
- tests for timeout/duplicate.

Status: **CONSISTENT / SPIKE-15 PROVEN FOR REPRESENTATIVE PATH**

---

# 4. Evidence

Canonical:
local capture
→ durable LocalEvidence
→ upload reservation
→ direct S3-compatible upload
→ server finalization verifies stored bytes/SHA-256
→ authoritative READY
→ completion can reference accepted evidence according bound EvidencePolicy.

Required consistency:
- EvidencePolicy requirement key/revision.
- local evidence metadata.
- server evidence metadata.
- object key opaque.
- auth around reserve/finalize/download.
- no public permanent restricted URL.
- UI distinguishes local/pending/ready/rejected.

Status: **CONSISTENT / SPIKE-12 + SPIKE-15 PROVEN**

---

# 5. Authorization

Canonical:
Keycloak authentication
→ HILTECH identity/context
→ OpenFGA relationship/action
→ application/domain obligation
→ command invariant
→ transaction/audit.

Required:
- role label not authority truth.
- UI hiding not enforcement.
- field redaction after object permission.
- offline replay re-authorized.
- configuration edit/activation permission separated.
- external organization boundary.

Status: **CONSISTENT / MODEL READY FOR EXACT FGA FILE**

---

# 6. Asset custody

Canonical:
- Asset current custodian/location are projections.
- AssetMovement is append-oriented history.
- one active authoritative custody.
- CheckoutAsset is collision-sensitive and OA by default.
- cached AVAILABLE may lose to newer authoritative checkout.
- second command gets explicit conflict.

Required:
- DB constraint/transaction strategy.
- baseVersion.
- movement row.
- projection update.
- API conflict payload.
- UI current custodian/context if permitted.
- test two concurrent checkouts.

Status: **CONSISTENT / DOMAIN CONTRACT REQUIRES EXACT DB SHAPE**

---

# 7. Project/Site storage

Canonical:
- physical storage model scales beyond today's locations.
- Main Warehouse and future warehouses use same location/custody platform.
- PROJECT_STORAGE / SITE_STORAGE can represent temporary remote material locations.
- temporary storage is not automatically a full standalone warehouse.
- stock movement links Project/Site/Work context.

Required:
- exact StorageLocation schema.
- parent/location rules.
- project/site optional/required by kind.
- transfer/issue/consume/return commands.
- UI location/context labels.

Status: **CONSISTENT / EXACT SCHEMA PENDING**

---

# 8. Offline state vs UI state

Canonical:
UI renders from local observable state.

Never show:
- server-complete when command is only queued.
- authoritative asset checkout when only local intent exists.
- accepted work before server acceptance.

Mapping candidate:

| Local/Server condition | UI |
|---|---|
| local draft saved | Saved on this device |
| PENDING/RETRYABLE | Waiting for connection / retry |
| SYNCING | Syncing |
| APPLIED | Synced |
| CONFLICT | Needs review / conflict |
| BLOCKED_BY_CONFLICT | Waiting on conflict resolution |
| FAILED_TERMINAL | Needs attention |
| server SUBMITTED | Awaiting review |
| server ACCEPTED | Accepted |

Status: **CONSISTENT**

---

# 9. Configuration lifecycle

Canonical:
DRAFT → ACTIVE → SUPERSEDED/RETIRED.

Required:
- ACTIVE material semantics immutable in-place.
- new revision for material policy change.
- historical usage binding preserved.
- activation online-authoritative.
- activation permission stronger than edit draft.
- configuration UI shows impact/dependencies.
- old offline job bundle remains explainable.

Status: **CONSISTENT / EXACT PERSISTENCE PENDING**

---

# 10. Field tracking

Canonical:
tracking is scoped operational behavior configured by FieldTrackingPolicy.

Required:
- no implied permanent tracking.
- explicit operational context.
- trigger/stop.
- visibility.
- retention.
- offline buffering rule.
- policy revision in job bundle when enabled.
- sensitive location data field-level protection.

Status: **CONSISTENT / LEGAL/RETENTION DETAILS PENDING**

---

# 11. Project progress

Canonical:
accepted work is the primary operational source of progress where possible.
No arbitrary percentage should silently override structured accepted work.

Required:
- accepted WorkOrder event.
- projection/read model.
- milestone/project weighting config where used.
- PM UI.
- client-visible projection only from approved subset.

Status: **CONSISTENT / EXACT PROGRESS MODEL PENDING**

---

# 12. Exact-version review

Canonical:
ReviewPolicy + submitted WorkOrder version.

Required:
- reviewer sees submitted version.
- AcceptWork references exact submitted/base version.
- any material change supersedes old review context.
- stale review action rejected.
- audit shows decision version.

Status: **CONSISTENT**

---

# 13. Configuration vs hard invariants

Configuration may change:
- WorkTypes
- readiness/evidence/review
- teams/roles/relationships
- locations/master data
- tracking
- notification/escalation

Configuration may NOT disable:
- server authorization
- audit for critical operations
- idempotency where required
- optimistic conflict protection
- one active custody
- no silent negative stock
- evidence checksum/finalize
- exact-version critical approval/review
- replay re-authorization

Status: **CONSISTENT**

---

# 14. Cross-contract blockers before first-slice freeze

Remaining structural closure:

1. exact Project/Site/WorkOrder contract.
2. exact Asset/Warehouse/Stock subset contract.
3. exact first-slice PostgreSQL table/constraint shape.
4. exact first-slice API DTO/routes/error schemas.
5. exact Room entities/cursors/payload serializer.
6. exact OpenFGA model + obligation map.
7. final representative UI render/RTL/conflict/adaptive proof.
8. provider/runtime/signing/backup choices required by Freeze Checklist.
9. reality validation only where it can reveal missing structure or non-configurable constraint.

---

# Review rule

Whenever a contract file changes, check whether it changes:
- DB ownership/schema,
- API payload,
- Room cache/queue,
- FGA/application policy,
- UI state,
- tests.

If yes, update all affected artifacts in the same freeze pass.

Current decision:
**configuration/offline/auth/UI contracts are mutually coherent enough to proceed to exact Project/Work and Asset/Warehouse contract candidates.**
