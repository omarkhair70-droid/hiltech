# 08 — First-Slice Production Test Matrix

Status: **CONTRACT CANDIDATE v0.1**
Date: 2026-09-18

SPIKE tests prove architecture.
These tests will prove the real production contracts.

---

# Configuration / Policy

Required:
- create/edit/validate draft.
- activate revision.
- supersede revision.
- unauthorized edit/activation deny.
- stale config baseVersion.
- duplicate activation idempotency.
- WorkOrder binds active WorkType/policy revisions.
- old WorkOrder retains historical binding after policy supersession.
- add WorkType without deploy.
- add SiteStorage without deploy.
- change EvidencePolicy/ReviewPolicy without deploy.
- invalid dependency graph rejected.
- configuration audit reconstructs old/new revision.

---

# Domain / Unit

## Project / Work
- Project/Site invariants.
- WorkOrder lifecycle/readiness separation.
- transition matrix.
- AssignmentPolicy eligibility.
- readiness policy derivation/waiver.
- EvidencePolicy completeness.
- exact-version review.
- rework/acceptance history.
- cancel vs stale offline submit.
- policy-binding history after supersession.
- progress derivation from accepted work where configured.

## Asset / Warehouse
- one active custody.
- append-only movement.
- checkout collision one winner.
- calibration block.
- return/condition path.
- temporary Project/Site storage.
- stock quantity conservation/no silent negative.
- reservation collision.
- stocktake/adjustment separation.

---

# Database

Required:
- migration from empty.
- migration from previous supported version.
- unique business codes.
- valid FK ownership.
- optimistic version collision.
- one active asset custody.
- append-only movement.
- idempotent operation.
- no negative stock if applicable.
- evidence READY constraint/path.

---

# API Contract

Required per frozen command:
- success.
- validation reject.
- permission deny/object hidden.
- invalid state.
- stale baseVersion.
- duplicate replay.
- re-auth if required.
- retryable safe mapping.
- correlation/trace.
- exact DTO compatibility on Android + Desktop.

Read models:
- field-level redaction.
- stable pagination/order if list.
- no cross-project/org leakage.

---

# Authorization

For every critical action:
- intended allow.
- unrelated employee deny.
- wrong project/work deny.
- external/client deny where applicable.
- expired delegation deny.
- revoked identity deny.
- offline permission changed before replay.

---

# Offline / Android

Required:
- bundle download.
- airplane/no network.
- process death.
- app restart.
- evidence local persistence.
- queued commands.
- reconnect.
- WorkManager replay.
- duplicate retry.
- stale conflict.
- dependent block.
- auth expired/revoked.
- app update with pending commands.
- local schema migration.
- insufficient storage / missing file behavior where realistic.

---

# Warehouse

Required:
- scan known asset.
- unknown/deactivated tag.
- reserve.
- checkout one winner under collision.
- already checked out.
- return.
- damaged return.
- calibration blocked.
- wrong recipient/context.
- custody correction policy.
- rapid repeated scan if real workflow requires.

---

# Evidence

Required:
- direct upload.
- checksum match.
- checksum mismatch.
- interrupted/retry.
- unauthorized read.
- signed URL expiry.
- duplicate finalize.
- cancelled/reassigned work with pending evidence.

---

# UI / Accessibility / RTL

Required:
- Arabic RTL.
- English LTR.
- mixed code/serial/ID.
- phone.
- field tablet if real.
- desktop.
- keyboard/focus on dense desktop.
- loading/empty/error.
- offline/queued/retry.
- stale/conflict.
- permission differences.
- screen-reader/accessibility baseline.

---

# Operational

Required:
- Windows install/update/rollback on production-like office device.
- Android install/update on representative field device.
- server deploy/health.
- DB backup/restore.
- object-storage recovery assumptions.
- Keycloak/OpenFGA config promotion.
- secret rotation.
- audit/trace diagnosis.
- rollback of release without losing queued/local state.

---

# Slice Acceptance Marker

The production slice is VERIFIED/OPERABLE only after a real/synthetic-safe scenario proves:

PM creates/assigns
→ Warehouse checkout
→ Technician receives durable bundle
→ offline execution/evidence
→ reconnect
→ server applies safely
→ Supervisor accepts
→ PM sees authoritative result
→ audit/trace reconstructs flow

with no bypass of frozen authorization, evidence, offline or custody contracts.


---

# Cross-contract consistency

The test suite must prove the same semantics across:
- PostgreSQL constraints,
- command handlers,
- API DTO/result mapping,
- Ktor client,
- Room queue/cache,
- OpenFGA/application policy,
- UI state.

Required consistency examples:
- VERSION_CONFLICT maps DB/handler/API/Room/UI coherently.
- policy revision binding shown in API/Room and preserved in DB.
- Evidence READY means same thing everywhere.
- CheckoutAsset success creates one movement + one custody projection.
- offline APPLIED only after authoritative success.
- field redaction matches API/read-model + UI.
- config ACTIVE revision selection matches DB/API/UI.

---

# Freeze criterion

This matrix becomes FROZEN when every first-slice command/read model has:
- unit/domain tests,
- DB constraint tests where relevant,
- API contract tests,
- authorization allow/deny tests,
- offline tests where relevant,
- UI state test/prototype evidence,
- operational/observability acceptance where relevant.

No first-slice feature is BUILD_READY with only a happy-path test.
