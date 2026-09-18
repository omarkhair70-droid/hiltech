# 02 — First-Slice API / Command / Read-Model Contracts

Status: **CONTRACT CANDIDATE v0.2 / ROUTE + DTO SHAPES DEFINED**

## Accepted Cross-Cutting Wire Rules

Locked technical:
- Ktor Client 3.5.2.
- Android OkHttp engine.
- JVM Desktop CIO engine.
- bearer OIDC access token.
- `X-Correlation-Id`.
- W3C `traceparent`.
- `Idempotency-Key` for retry-sensitive commands.
- `baseVersion` in concurrency-sensitive command body.
- JSON through shared typed DTOs.
- explicit commands, not unrestricted CRUD.
- binary files use reservation/direct upload/finalization.

Major API prefix `/v1` is accepted as current baseline.
Exact resource grammar is frozen here before bootstrap.

---

# Configuration API

Normal operating-policy changes use explicit configuration commands, not developer/database edits.

Command families:
- CreateDraftConfiguration
- UpdateDraftConfiguration
- ValidateDraftConfiguration
- ActivateConfigurationRevision
- SupersedeConfigurationRevision
- RetireConfiguration
- CloneConfigurationRevision

Required query/read models:
- ActiveConfigurationByCode
- ConfigurationRevisionHistory
- ConfigurationDependencyGraph
- ConfigurationUsageImpact
- DraftValidationResult
- ConfigurationCenterList
- WorkTypeConfigurationDetail

All config mutations:
- server-authorized,
- audited,
- baseVersion protected,
- idempotent where retry-sensitive.

Exact resource grammar is still to be frozen, but the capability boundary is now part of first production scope.

---

# Command Freeze Table

The earlier placeholder command table has been superseded by the v0.2 route/DTO section below.

Current command families are now structurally defined for:
- WorkOrder lifecycle,
- Asset custody,
- Evidence upload/finalize,
- Stock movement,
- typed Configuration revisions.

Remaining work is exact field/route normalization and error-code closure, not reality discovery.

---

# Query / Read Model Freeze Table

Read-model structural shapes are defined in the v0.2 section below:

- PMProjectCommandCenter
- TechnicianTodayItem
- TechnicianJobBundle
- WarehouseAssetPassport
- WarehouseCheckoutContext
- SupervisorReviewView
- PMWorkProgressItem

Remaining design work affects presentation/navigation and exact optional fields; authorization/redaction and core semantics are already contract-bound.

---

# Standard Error Families

Accepted semantic families:
- UNAUTHENTICATED
- REAUTH_REQUIRED
- PERMISSION_DENIED
- OBJECT_NOT_VISIBLE
- REJECTED_VALIDATION
- REJECTED_STATE
- VERSION_CONFLICT
- DUPLICATE_REPLAY
- FAILED_RETRYABLE
- FAILED_PERMANENT
- INTEGRATION_PENDING
- INTEGRATION_UNKNOWN

Before freeze, every command above must list exactly which errors it may return and the safe `details` schema.

---

# Conflict Contract

SPIKE-15 validated:
- stale `baseVersion` does not overwrite authoritative state,
- local intent/evidence remains,
- dependent local command can become BLOCKED_BY_CONFLICT.

First-slice freeze must define exact `conflictType` and `allowedRecoveryActions` per Work/Asset collision.

Never return a generic conflict with no recovery semantics to field UI.


---

# Route grammar candidate v0.2

Principles:
- /v1 major prefix.
- plural nouns for resources/read models.
- state-changing business actions use explicit command sub-routes.
- no unrestricted generic PATCH for lifecycle-critical objects.
- UUIDs in path where object identity is required.
- command bodies carry typed payload + operationId/baseVersion where relevant.

## Project / Work

- POST /v1/projects/{projectId}/work-orders — CreateWorkOrder
- GET /v1/projects/{projectId}/command-center — PMProjectCommandCenter
- GET /v1/projects/{projectId}/work-orders — scoped list/read model
- GET /v1/work-orders/{workOrderId} — authorized detail
- GET /v1/work-orders/{workOrderId}/job-bundle — TechnicianJobBundle
- POST /v1/work-orders/{workOrderId}/assign
- POST /v1/work-orders/{workOrderId}/start
- POST /v1/work-orders/{workOrderId}/block
- POST /v1/work-orders/{workOrderId}/resume
- POST /v1/work-orders/{workOrderId}/submit-completion
- POST /v1/work-orders/{workOrderId}/accept
- POST /v1/work-orders/{workOrderId}/request-rework
- POST /v1/work-orders/{workOrderId}/close
- POST /v1/work-orders/{workOrderId}/cancel
- GET /v1/field/today — TechnicianToday
- GET /v1/review/work-items — reviewer queue

## Asset / Warehouse

- GET /v1/assets/{assetId}/passport
- GET /v1/assets/by-tag/{opaqueTagCode}
- POST /v1/assets/{assetId}/reserve
- POST /v1/assets/{assetId}/checkout
- POST /v1/assets/{assetId}/return
- POST /v1/assets/{assetId}/transfer
- POST /v1/assets/{assetId}/report-damage
- POST /v1/assets/{assetId}/report-missing
- GET /v1/storage-locations
- GET /v1/warehouse/checkout-context?assetId=...
- POST /v1/stock/{stockItemId}/reserve
- POST /v1/stock/{stockItemId}/issue
- POST /v1/stock/{stockItemId}/consume
- POST /v1/stock/{stockItemId}/return
- POST /v1/stock/transfers
- POST /v1/stocktakes
- POST /v1/stocktakes/{stocktakeId}/counts

## Evidence

- POST /v1/evidence/upload-reservations
- POST /v1/evidence/{evidenceId}/finalize
- GET /v1/evidence/{evidenceId}
- POST /v1/evidence/{evidenceId}/download-reservation

## Configuration

Typed route families:

- /v1/config/work-types
- /v1/config/assignment-policies
- /v1/config/readiness-policies
- /v1/config/evidence-policies
- /v1/config/review-policies
- /v1/config/tracking-policies
- /v1/config/asset-types
- /v1/config/stock-categories
- /v1/config/storage-locations

Each family supports command semantics equivalent to:
- POST .../drafts
- PUT .../drafts/{configRevisionId}
- POST .../{configRevisionId}/validate
- POST .../{configRevisionId}/activate
- POST .../{configRevisionId}/supersede
- POST .../{configRevisionId}/retire
- POST .../{configRevisionId}/clone
- GET .../{configRevisionId}
- GET .../{configRevisionId}/history
- GET .../{configRevisionId}/usage-impact

Exact wording can still be normalized once during final API review, but semantic route separation is accepted.

---

# Common command fields

Every retry-sensitive command DTO contains:

- operationId: UUID
- baseVersion: Long?
- clientOccurredAt: Instant

Headers:
- Authorization: Bearer ...
- Idempotency-Key: operationId
- X-Correlation-Id
- traceparent

Optional client metadata may later include app version, platform and installation ID only if server behavior needs it.

---

# Work command DTO candidates

## CreateWorkOrderRequest

- operationId
- siteId
- areaId?
- workPackageId?
- workTypeCode or active WorkTypeDefinition id
- title
- description?
- plannedStart?
- plannedEnd?
- priorityCode?
- instruction payload/template inputs
- initial assignment? optional if policy allows
- clientOccurredAt

Project id comes from route.

## AssignWorkRequest

- operationId
- baseVersion
- assignmentTargets: non-empty typed list
- plannedStart?
- plannedEnd?
- reason?
- clientOccurredAt

## StartWorkRequest

- operationId
- baseVersion
- clientOccurredAt
- localSiteSessionRef?

## BlockWorkRequest

- operationId
- baseVersion
- blockerTypeCode
- severityCode?
- description
- clientOccurredAt

## ResumeWorkRequest

- operationId
- baseVersion
- blockerId?
- resolutionNote?
- clientOccurredAt

## SubmitWorkCompletionRequest

- operationId
- baseVersion
- evidenceIds
- measurementOrTestRefs
- materialUsageRefs
- completionNote?
- clientOccurredAt

## AcceptWorkRequest

- operationId
- baseVersion = exact submitted version
- reviewStepId?
- comment?
- clientOccurredAt

## RequestReworkRequest

- operationId
- baseVersion
- reasonCode?
- reason
- requiredCorrectionNotes?
- evidenceRefs?
- clientOccurredAt

## CancelWorkRequest

- operationId
- baseVersion
- reasonCode
- reason
- clientOccurredAt

## WorkOrderCommandResult

- workOrderId
- lifecycleState
- readinessState
- version
- policyBindingSummary
- appliedAt
- correlationId
- duplicateReplay: Boolean

---

# Asset command DTO candidates

## CheckoutAssetRequest

- operationId
- baseVersion
- recipientTargetType
- recipientTargetId
- projectId?
- siteId?
- workOrderId?
- destinationStorageLocationId?
- expectedReturnAt?
- conditionAtCheckout
- reasonCode?
- clientOccurredAt

## AssetCustodyCommandResult

- assetId
- lifecycleState
- assetVersion
- movementId
- currentStorageLocationId?
- currentCustodianTargetType?
- currentCustodianTargetId?
- projectId?
- siteId?
- workOrderId?
- expectedReturnAt?
- appliedAt
- correlationId
- duplicateReplay

Return and Transfer use the same exact-version/idempotent pattern.

---

# Evidence DTO candidates

## ReserveEvidenceUploadRequest

- operationId
- targetType
- targetId
- workOrderId?
- evidenceRequirementKey?
- evidenceTypeCode
- contentType
- sizeBytes
- expectedSha256
- clientOccurredAt
- baseVersion?

## ReserveEvidenceUploadResult

- evidenceId
- uploadSessionId
- uploadUrl
- requiredHeaders
- expiresAt
- maxSizeBytes
- correlationId

## FinalizeEvidenceRequest

- operationId
- uploadSessionId
- expectedSha256
- expectedSizeBytes

## EvidenceResult

- evidenceId
- storageState
- sha256
- sizeBytes
- contentType
- finalizedAt?
- version
- correlationId

---

# Standard list/read envelopes

List/read models use:
- items
- nextCursor: String?
- asOf: Instant
- correlationId

Cursor is opaque to clients.

Single business commands may return typed result DTOs directly instead of a generic data wrapper.

---

# Standard error envelope

Candidate exact shape:

- code: String
- message: String
- correlationId: String
- retryable: Boolean
- details: object?
- currentVersion: Long?
- conflict: ConflictPayload?

## ConflictPayload

- conflictType
- targetType
- targetId
- attemptedBaseVersion
- currentVersion
- safeCurrentState
- localWorkSafe: Boolean?
- allowedRecoveryActions: List<String>

Rules:
- safeCurrentState is authorization-filtered.
- external actors may receive OBJECT_NOT_VISIBLE instead of existence-revealing details.
- clients never parse free-text message to drive logic.

---

# HTTP status mapping candidate

- 200/201 — applied/read success.
- 202 — only for explicitly asynchronous server workflow.
- 400 — malformed/request validation.
- 401 — unauthenticated/token invalid.
- 403 — permission denied where existence disclosure is safe.
- 404 — not found/object hidden.
- 409 — VERSION_CONFLICT / state conflict / custody collision.
- 422 — domain validation/evidence/policy requirement not met.
- 429 — rate limited.
- 500 — safe internal failure.
- 503 — retryable service/integration unavailable.

---

# Read-model candidate shapes

## TechnicianTodayItem

- workOrderId/code
- WorkType code/name
- Project/Site safe labels
- lifecycleState/readinessState
- plannedStart/end
- priorityCode
- sync/freshness hint
- navigation/location availability flag
- blocker summary
- tool/material readiness summary

## TechnicianJobBundle

Uses the exact local contract in 04_ROOM_OFFLINE_SYNC.md.

## WarehouseAssetPassport

- Asset identity/type/model/serial
- lifecycle/condition
- calibration state/due
- current safe custody/location
- reservation context
- Project/Site/Work context where permitted
- tag identity
- version
- freshness/asOf
- acquisition cost excluded unless separate permission

## SupervisorReviewView

- WorkOrder identity
- exact submitted version
- WorkType
- instruction revision
- review policy step
- evidence requirement/result set
- measurements/tests
- relevant drawings/revisions
- assignment/context
- prior rework/review history
- allowed actions

## PMProjectCommandCenter

- Project identity/state/health
- milestones/progress projection
- Work waiting/blocked/submitted/rework
- resource readiness
- Warehouse/material exceptions
- client/action exceptions
- activity/audit links
- finance subset only with permission

---

# API freeze items still open

- exact string/max field constraints.
- exact route normalization for configuration revisions.
- exact cursor implementation.
- exact client metadata headers.
- exact REAUTH_REQUIRED response details.
- object-hidden policy by API audience.
- exact DTO representation for typed assignment target and requirement instances.
- exact Project/Site create/update routes beyond first slice.

Current:
**first-slice API has stable route/DTO/error candidates sufficient to drive server/client skeleton generation.**
