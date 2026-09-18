# 04 — Room / Offline / Sync Contract

Status: **PRE-FREEZE TEMPLATE**

## Locked Technical Semantics

- Room3/SQLite local store — ADR-006.
- local database is the client observable source for offline-capable surfaces.
- server remains authoritative business truth.
- typed commands are queued, not arbitrary record merges.
- operationId stays stable across retries.
- baseVersion protects collision-sensitive commands.
- WorkManager schedules constrained replay.
- stale conflict is explicit.
- dependent commands may become BLOCKED_BY_CONFLICT.
- local evidence/intent survives conflict/process death.

---

# Technician Job Bundle

Exact cached schema must be frozen from real field workflow.

Required categories likely include:
- WorkOrder identity/state/version,
- Project/Site safe context,
- assignment/reviewer context,
- instruction version,
- readiness summary,
- required evidence policy,
- required assets/materials,
- drawing/document refs,
- access instructions limited to authorized need,
- sync metadata.

Do not cache sensitive Project/Site fields merely because they exist server-side.

---

# Pending Command Record

Semantics are locked.

Exact production schema must cover:
- operationId,
- commandType,
- target type/id,
- baseVersion,
- serialized typed payload/ref,
- clientOccurredAt,
- localSequence,
- dependency operation IDs,
- queue state,
- retry count,
- last attempt,
- last result code,
- server result/version ref.

Freeze exact enum names and serialization format here.

---

# Queue States

Semantics:
- PENDING
- RETRYABLE
- SYNCING
- APPLIED
- CONFLICT
- BLOCKED_BY_CONFLICT
- FAILED_TERMINAL

Renaming is allowed before schema freeze; semantics are not.

---

# Evidence Queue

Freeze:
- local file identity/path policy,
- evidenceId,
- work/object link,
- SHA-256,
- MIME/type,
- size,
- capture metadata,
- upload reservation state,
- upload attempt state,
- finalized server ref,
- retention/delete-after-sync policy.

Local file must not disappear before authoritative finalize or explicit discard.

---

# Conflict Record / UX Contract

For each conflict class freeze:
- attempted command,
- baseVersion,
- current server version/state,
- local evidence refs,
- conflict type,
- safe server summary,
- allowed recovery actions,
- who can resolve,
- whether later local commands unblock/rebase/discard.

First required collision:
PM reassign/cancel/version-change while technician has offline work.

---

# Local Migration Policy

Must define:
- Room schema versioning,
- migration tests,
- supported upgrade path,
- downgrade/rollback expectations,
- what local state survives app update,
- treatment of old queued commands after contract version changes.

Windows local-store migration policy must remain compatible with ADR-012 rollback rules.

---

# Sync Contract Tests

Required:
- restart persistence,
- process death,
- no connectivity,
- reconnect,
- ambiguous network retry,
- duplicate replay,
- stale conflict,
- dependent block,
- evidence preservation,
- auth revoked while offline,
- command no longer permitted after reconnect,
- app update with pending queue,
- corrupted/missing local evidence behavior.
