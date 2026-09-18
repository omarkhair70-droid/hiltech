# 04 — Room / Offline / Sync Contract

Status: **CONTRACT CANDIDATE v0.2 / LOCAL ENTITY SHAPES DEFINED**
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


---

# Exact Room entity candidates

Physical Room entity/table names are candidate production names.

## local_work_order

- work_order_id: UUID/String PK
- work_order_code: String
- project_id
- site_id
- area_id?
- work_type_code
- lifecycle_state
- readiness_state
- work_order_version: Long
- policy_binding_hash/ref
- planned_start?
- planned_end?
- priority_code
- instruction_revision
- fetched_at
- freshness_expires_at?
- bundle_revision
- local_sync_state
- updated_at

Indexes:
- lifecycle_state + planned_start
- project_id
- site_id

This is a read/cache projection, not authoritative mutation storage.

## local_project_context

- project_id PK
- project_code
- display_name
- lifecycle_state
- safe_health_summary?
- source_version
- fetched_at

Only authorized/safe subset.

## local_site_context

- site_id PK
- project_id
- site_code
- display_name
- address_text?
- latitude?
- longitude?
- access_instructions?
- timezone?
- source_version
- fetched_at

Sensitive fields only if needed by assigned work.

## local_policy_binding

- work_order_id PK
- work_type_definition_id
- work_type_revision
- assignment_policy_id
- assignment_policy_revision
- readiness_policy_id
- readiness_policy_revision
- evidence_policy_id
- evidence_policy_revision
- review_policy_id
- review_policy_revision
- tracking_policy_id?
- tracking_policy_revision?
- checklist_template_id?
- checklist_template_revision?
- instruction_template_id?
- instruction_template_revision?
- binding_created_at

## local_requirement_instance

- id PK
- work_order_id
- requirement_family
- requirement_key
- label
- required
- local_or_authoritative_state
- source_config_id?
- source_config_revision?
- sort_order
- updated_at

Used to render readiness/evidence/tool/material requirements offline.

## local_assignment

- id PK
- work_order_id
- target_type
- target_id
- display_label
- lead
- state
- source_version
- updated_at

## local_document_ref

- id PK
- work_order_id
- document_id
- document_type_code
- title
- revision_code/version
- local_file_ref?
- downloaded
- source_version
- fetched_at
- freshness_expires_at?

## local_asset_ref

- asset_id PK/composite with work_order_id if projection-specific
- work_order_id?
- asset_code
- type_label
- lifecycle_state
- condition_state
- calibration_summary
- custody_summary
- tag_code?
- source_version
- fetched_at

## pending_command

- operation_id PK
- actor_user_identity_id
- device_id
- command_type
- target_type
- target_id
- base_version?
- payload_version
- payload_json_or_blob
- client_occurred_at
- enqueued_at
- local_sequence
- state
- retry_count
- next_retry_at?
- last_attempt_at?
- last_result_code?
- last_server_version?
- last_correlation_id?
- contract_version
- policy_binding_ref/hash?
- updated_at

Indexes:
- state + next_retry_at
- target_type + target_id
- local_sequence

## pending_command_dependency

- operation_id
- depends_on_operation_id
- PRIMARY KEY(operation_id, depends_on_operation_id)

Cycle must be rejected when queueing.

## local_evidence

- evidence_id PK
- work_order_id
- target_type
- target_id
- requirement_key?
- evidence_policy_id?
- evidence_policy_revision?
- evidence_type_code
- local_file_ref
- sha256
- content_type
- size_bytes
- captured_at
- captured_by_user_id
- source_device_id
- local_state
- upload_session_id?
- remote_object_ref?
- finalized_server_evidence_id?
- upload_attempt_count
- last_upload_error_code?
- updated_at

Indexes:
- work_order_id + local_state
- local_state

## conflict_record

- id PK
- operation_id UNIQUE
- command_type
- target_type
- target_id
- conflict_type
- base_version?
- current_server_version?
- safe_server_state_json
- allowed_recovery_actions_json
- detected_at
- resolved_at?
- resolution_type?
- resolution_operation_id?

## sync_cursor

- scope_key PK
- cursor
- last_successful_pull_at
- server_as_of?
- updated_at

## sync_diagnostic

Do not persist unlimited diagnostics.

Candidate bounded fields:
- last_successful_push_at?
- last_successful_pull_at?
- pending_count
- conflict_count
- failed_count
- binary_backlog_count
- oldest_pending_at?

Sensitive raw payloads are not diagnostic output.

---

# Room ownership / DAO boundaries

Candidate DAOs:

- WorkBundleDao
- RequirementDao
- AssignmentDao
- DocumentCacheDao
- AssetCacheDao
- PendingCommandDao
- EvidenceQueueDao
- ConflictDao
- SyncCursorDao

UI/domain does not call raw SQL or Ktor directly.

Repository/data layer combines:
- Room observable queries.
- sync orchestration.
- Ktor authoritative refresh/commands.

---

# Local transaction rules

When creating an offline command:
1. validate local preconditions possible to know.
2. persist local intent/state change + PendingCommand atomically.
3. never mark authoritative APPLIED.
4. enqueue WorkManager after local transaction commits.

When sync succeeds:
1. apply authoritative server DTO to local projection.
2. mark command APPLIED.
3. record returned server version.
4. resolve dependent commands if eligible.

When sync conflicts:
1. preserve local evidence/intent.
2. create/update ConflictRecord.
3. mark command CONFLICT.
4. mark unsafe dependents BLOCKED_BY_CONFLICT.
5. apply safe current server projection separately.

---

# Room schema version policy candidate

- schema version integer increments on every physical schema change.
- exported Room schema committed for migration verification.
- migration tests run from every supported app schema version to current.
- no destructive fallback in production for unresolved user work.
- queued payload contractVersion is independent from Room schema version.
- app startup must detect unsupported queued payloads and preserve them for controlled migration/recovery.

---

# Local retention candidate

Delete/evict only when safe:

Work bundles:
- retain assigned/recent work according local cache policy.
- unresolved conflicts/pending commands pin required context.

Evidence:
- pinned until READY + retention rule or explicit discard.
- conflict-linked evidence pinned until resolution.

Documents:
- cache policy by assignment/freshness/storage budget.

Commands/audit diagnostics:
- APPLIED rows may be compacted after server result durability + support retention window.
- CONFLICT/FAILED remain until resolved/retention policy.

---

# Open local-schema items before final freeze

- exact local encryption-at-rest implementation.
- cache size/eviction numbers.
- supported migration-version window.
- exact pull/cursor scope keys.
- whether Desktop uses same local schema abstraction or a platform-specific equivalent.

Current:
**Android first-slice Room schema is specific enough to create entities/DAOs/migration skeletons after Freeze.**


---

# Local representation conventions

## UUID

Room stores UUID domain IDs as canonical lowercase string/TEXT.

Reasons:
- KMP/platform portability.
- stable wire/local representation.
- avoids platform-specific binary UUID adapters.

Domain layer may wrap IDs in typed value classes.

## Time

Instant is stored as epoch milliseconds or ISO string only through one shared converter contract.
Candidate preference:
- epoch milliseconds Long for indexed/sortable local fields.
- wire remains ISO-8601 Instant.

Do not mix representations per entity.

## Command payload serialization

PendingCommand payload:
- UTF-8 JSON.
- kotlinx.serialization.
- explicit commandType discriminator stored outside payload.
- payloadVersion integer required.
- unknown persisted payload versions are never interpreted as current schema.

The raw JSON is internal local persistence, not public API truth.

## JSON network compatibility

Client response decoder:
- additive-response tolerant.
- unknown response fields ignored.

Command/request DTOs remain compile-time typed.
Server domain validation rejects invalid semantic values.

## Queue ordering

localSequence is monotonically increasing per installation/database.
operationId is global semantic retry identity.
Ordering never substitutes for dependency edges.

## Database write pattern

- all local state + pending command writes happen transactionally.
- WorkManager enqueue happens after successful DB transaction.
- worker can be recreated entirely from Room state after process death.

