# 08 — First-Slice Production Test Matrix

Status: **PRE-FREEZE TEMPLATE**

SPIKE tests prove architecture.
These tests will prove the real production contracts.

---

# Domain / Unit

Required:
- Project/Site invariants.
- WorkOrder transition matrix.
- readiness rules.
- evidence completeness rules.
- rework/acceptance rules.
- Asset custody invariants.
- calibration availability rule if in scope.
- stock quantity invariants if in scope.

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
