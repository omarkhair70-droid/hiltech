# 04 — Room / Offline / Sync Contract

Status: **CONTRACT CANDIDATE v0.1**
Date: 2026-09-18

## Accepted technical baseline

- Room3/SQLite local store — ADR-006.
- local DB is the observable client source for offline-capable surfaces.
- server remains authoritative business truth.
- Ktor Client 3.5.2 is the shared sync transport — ADR-007.
- typed commands are queued; raw business-record merge is not the default.
- stable operationId protects retry/idempotency.
- baseVersion protects collision-sensitive commands.
- WorkManager owns constrained background replay on Android.
- stale conflicts are explicit.
- dependent commands can become BLOCKED_BY_CONFLICT.
- local evidence/intent survives conflict and process death.
- authorization is re-evaluated on replay.
- SPIKE-15 proved bundle → offline → process death → reconnect → replay → conflict behavior.

Canonical supporting docs:
- `docs/09-offline-sync/OFFLINE_FIRST_MODEL.md`
- `docs/09-offline-sync/SYNC_ENGINE.md`
- `docs/09-offline-sync/CONFLICT_POLICY.md`
- `docs/09-offline-sync/OFFLINE_CLASSIFICATION_MATRIX.md`

---

# 1. Local database ownership

Room is local client state, not a second authoritative business database.

First-slice local ownership families:

## job_bundle
Durable cached Project/Site/Work context required for field execution.

## pending_command
Typed local intent waiting for authoritative server evaluation.

## local_evidence
Local metadata + durable file reference for evidence not yet finalized server-side.

## sync_cursor
Per-scope/read-model pull progress when a cursor/change-feed contract is adopted.

## conflict_record
Explicit unresolved command/object conflict for UI/support.

## cached_reference
Small authorized reference data required for assigned work:
- user/team display refs,
- storage/asset refs,
- document/drawing metadata,
- policy labels/requirements.

Sensitive server-only data is not copied merely because an object exists.

---

# 2. TechnicianJobBundle

## Purpose

Allow assigned field work to continue through poor/no connectivity while preserving the exact configuration/policy context needed to explain the job.

## Candidate identity fields

- workOrderId: UUID
- workOrderVersion: Long
- workTypeDefinitionId: UUID
- workTypeRevision: Int
- projectId: UUID
- projectCode: String
- projectDisplayName: String
- siteId: UUID?
- siteCode: String?
- siteDisplayName: String?
- areaContext: typed minimal structure?
- fetchedAt: Instant
- serverGeneratedAt: Instant
- freshnessExpiresAt: Instant?
- bundleRevision: Long/String

## Assignment/context

- assignmentTargetType
- assigneeIds / crew/team ref
- leadUserId?
- reviewer relationship summary
- plannedStart/End?
- priorityCode

## Policy binding snapshot

- assignmentPolicyId + revision
- readinessPolicyId + revision
- evidencePolicyId + revision
- reviewPolicyId + revision
- trackingPolicyId + revision?
- checklistTemplateId + revision?
- instructionTemplateId + revision?

The bundle carries enough immutable policy identity to render requirements offline.
It does not make the device authoritative over policy activation.

## Work content

- lifecycleState
- readinessState
- instructionRevision
- instruction payload/safe rendered form
- readiness requirement instances
- evidence requirement instances
- required asset/tool refs
- required material refs
- document/drawing refs + revision/freshness
- allowed local action set as a UI hint only
- site contact/access subset
- tracking policy subset when enabled

## Security

- include only fields needed for this user's assigned work.
- no internal cost/margin.
- no unrelated employee/private data.
- security-sensitive site notes minimized.
- cached access does not override server authorization on replay.

---

# 3. PendingCommand

## Exact semantic fields

- operationId: UUID
- actorUserIdentityId: UUID
- deviceId: UUID/String
- commandType: String/typed discriminator
- targetType: String
- targetId: UUID
- baseVersion: Long?
- payloadVersion: Int
- payloadJson/typed serialized payload
- clientOccurredAt: Instant
- enqueuedAt: Instant
- localSequence: Long
- dependencyOperationIds: List<UUID>
- state: PendingCommandState
- retryCount: Int
- nextRetryAt: Instant?
- lastAttemptAt: Instant?
- lastResultCode: String?
- lastServerVersion: Long?
- lastCorrelationId: String?
- contractVersion: Int
- createdUnderPolicyBindingHash/ref?
- updatedAt: Instant

## PendingCommandState

Freeze candidate enum:
- PENDING
- RETRYABLE
- SYNCING
- APPLIED
- CONFLICT
- BLOCKED_BY_CONFLICT
- FAILED_TERMINAL
- CANCELLED_LOCAL

Rules:
- APPLIED is server-confirmed only.
- CONFLICT is not auto-retried.
- BLOCKED_BY_CONFLICT remains blocked until dependency resolution.
- FAILED_TERMINAL requires explicit user/support path.
- CANCELLED_LOCAL only applies where local intent has not been authoritative-applied.

---

# 4. Command dependency semantics

Example:
1. capture evidence metadata/file locally.
2. reserve/upload/finalize evidence when online.
3. submit completion referencing finalized evidence.

The queue stores dependencies explicitly.
Timestamp order alone is insufficient.

A dependent command cannot become APPLIED before its required dependency is authoritative-ready.

---

# 5. LocalEvidence

Candidate fields:

- evidenceId: UUID
- targetType
- targetId
- workOrderId
- evidenceRequirementKey
- evidencePolicyId + revision
- localFileUri/pathRef
- sha256
- contentType
- sizeBytes
- capturedAt
- capturedByUserId
- sourceDeviceId
- localState
- uploadReservationId?
- remoteObjectRef?
- finalizedServerEvidenceId?
- uploadAttemptCount
- lastUploadErrorCode?
- retainUntilAuthoritative: Boolean
- createdAt/updatedAt

LocalEvidenceState candidate:
- LOCAL_READY
- RESERVATION_REQUIRED
- RESERVED
- UPLOADING
- UPLOADED_UNVERIFIED
- READY
- RETRYABLE
- REJECTED
- DISCARDED_EXPLICITLY

Invariant:
local file is not deleted before READY or explicit safe discard/retention policy.

---

# 6. ConflictRecord

Candidate fields:

- id: UUID
- operationId: UUID
- commandType
- targetType/id
- conflictType
- baseVersion
- currentServerVersion
- attemptedPayloadSummary
- safeServerStateSummary
- localEvidenceIds
- allowedRecoveryActions
- resolverRelationship/authority hint
- detectedAt
- resolvedAt?
- resolutionType?
- resolutionOperationId?

First-slice conflict classes:
- STALE_ASSIGNMENT
- RESOURCE_CUSTODY
- DOCUMENT_REVISION
- PERMISSION_REVOKED
- OBJECT_LIFECYCLE_CHANGED
- GENERIC_VERSION_CONFLICT only as fallback, not ideal UX.

No universal last-write-wins.

---

# 7. Offline class for first-slice actions

| Capability | Class | Contract |
|---|---|---|
| assigned project/site read | CR | freshness/version visible |
| WorkOrder bundle | CR | prefetched durable bundle |
| StartWork | LF | queue + baseVersion |
| BlockWork | LF | queue |
| ResumeWork | LF | queue |
| capture evidence | LF | durable local file + metadata |
| measurements/tests | LF | typed local record |
| SubmitWorkCompletion | LF | queued after dependencies |
| Supervisor AcceptWork | OA | exact submitted version |
| Assign/Reassign/Cancel | OA | collision-sensitive authority |
| asset passport read | CR | stale indicator |
| asset scan/identify | CR/MIXED | local known identity, server refresh |
| CheckoutAsset | OA default | authoritative custody |
| ReturnAsset capture | LF/MIXED | final receipt policy |
| stock count draft | LF | local draft |
| stock issue | OA default | quantity collision |
| config/policy activation | OA | privileged/versioned |
| Configuration Center read | CR/OA | cached list okay, activation authoritative |

---

# 8. Policy/configuration changes while offline

Rules:
- local bundle keeps the policy revisions it was built with.
- server re-evaluates current authorization and state on replay.
- a new policy revision does not silently mutate historical local evidence.
- if a configuration change materially invalidates pending work, server returns explicit state/policy conflict or rejection.
- UI preserves local work/evidence and explains recovery.
- configuration objects themselves are not edited/activated offline in first release.

---

# 9. Pull/change model

Candidate baseline:
- scoped pull per user/context.
- opaque cursor per read-model scope.
- object version remains available for targeted refresh.
- realtime events are hints; local DB remains render source.

Still to freeze:
- exact cursor token format,
- endpoint/resource grammar,
- invalidation rules by read model,
- retention of old bundle revisions.

---

# 10. WorkManager contract

Accepted:
- network-constrained background sync.
- queued work survives process death.
- retry uses stable operationId.
- UI state is recoverable from Room, not in-memory worker state.
- worker failure cannot erase pending commands/evidence.

Candidate work names:
- immediate-sync
- evidence-upload
- periodic-refresh
- explicit-user-retry

Exact scheduling/backoff constants remain implementation freeze details.

---

# 11. Local migration contract

Must support:
- forward Room migration from every supported app version.
- migration test fixtures.
- pending command/evidence preservation.
- policy-binding preservation.
- no destructive migration of unresolved queue/evidence.
- contractVersion/payloadVersion handling for queued commands.
- app update with pending commands.

Candidate rule:
if a queued command payload version is no longer executable, it becomes FAILED_TERMINAL/REQUIRES_APP_UPDATE_MIGRATION with preserved raw intent/evidence, not silently discarded.

---

# 12. Required tests

Already architecture-proven:
- process death.
- reconnect.
- duplicate replay.
- stale conflict.
- dependent block.
- evidence preservation.

Production contract tests additionally require:
- app update with pending command.
- Room schema migration.
- config/policy revision changed while offline.
- permission revoked while offline.
- WorkOrder cancelled/reassigned while offline.
- evidence reservation expires.
- missing/corrupt local evidence.
- low storage condition handling.
- old bundle freshness warning.
- document revision advanced.
- auth/session refresh failure.
- server 4xx/5xx safe mapping.
- queue diagnostics/support export without private raw payload leakage.

---

# 13. Open items before final freeze

- exact Room table/entity names.
- exact serialized command payload format and serializer settings.
- cursor/pull endpoint.
- local encryption-at-rest approach for restricted cached data.
- maximum cache/evidence budgets.
- exact retry/backoff constants.
- old-bundle retention policy.
- Windows local DB parity details for future shared workflows.

Current decision:
**offline architecture and first-slice semantics are contract-ready; remaining items are implementation-level freeze details, not architecture discovery.**
